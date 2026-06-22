import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap, takeUntil } from 'rxjs/operators';
import { SolrSearchService, SearchResult, SearchResponse } from '../../services/solr-search.service';

@Component({
  selector: 'app-search',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './search.component.html',
  styleUrls: ['./search.component.css']
})
export class SearchComponent implements OnInit, OnDestroy {

  searchControl = new FormControl('');
  results: SearchResult[] = [];
  total = 0;
  isLoading = false;
  hasSearched = false;
  private destroy$ = new Subject<void>();

  constructor(private solrService: SolrSearchService) {}

  ngOnInit() {
    this.searchControl.valueChanges.pipe(
      debounceTime(300),          // wait 300ms after user stops typing
      distinctUntilChanged(),     // only if value actually changed
      switchMap(query => {        // cancel previous request, start new
        this.isLoading = true;
        this.hasSearched = true;
        return this.solrService.search(query || '');
      }),
      takeUntil(this.destroy$)    // clean up on component destroy
    ).subscribe((response: SearchResponse) => {
      this.results = response.results;
      this.total = response.total;
      this.isLoading = false;
    });
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  pageUrl(id: string): string {
    return `http://localhost:4502${id}.html`;
  }
}