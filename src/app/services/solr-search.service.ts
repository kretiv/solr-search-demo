import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { map, catchError } from 'rxjs/operators';
import { environment } from '../../environments/environment';

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

  private solrUrl = environment.solrUrl;

  constructor(private http: HttpClient) {}

  search(query: string): Observable<SearchResponse> {

    // if empty query get everything; otherwise search title, description and id by wildcard
    const q = query.trim() === ''
      ? '*:*'
      : `title:${query}* OR content:${query}*`;

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