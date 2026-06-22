import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { map, catchError } from 'rxjs/operators';

export interface SearchResult {
  id: string;
  title?: string;
  description?: string;
  author?: string;
  cat?: string[];
}

export interface SearchResponse {
  results: SearchResult[];
  total: number;
  query: string;
}

@Injectable({
  providedIn: 'root'
})
export class SolrSearchService {

  // your local SOLR instance
  private solrUrl = '/solr/aem_content/select';

  constructor(private http: HttpClient) {}

  search(query: string): Observable<SearchResponse> {

    // if empty query get everything
    const q = query.trim() === '' ? '*:*' : query;

    const params = new HttpParams()
      .set('q', q)
      .set('wt', 'json')
      .set('rows', '20');

    return this.http.get<any>(this.solrUrl, { params }).pipe(
      map(response => ({
        results: response.response.docs,
        total: response.response.numFound,
        query: query
      })),
      catchError(error => {
        console.error('SOLR search error', error);
        return of({ results: [], total: 0, query });
      })
    );
  }
}