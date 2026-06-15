import { TestBed } from '@angular/core/testing';

import { SolrSearch } from './solr-search';

describe('SolrSearch', () => {
  let service: SolrSearch;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(SolrSearch);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
