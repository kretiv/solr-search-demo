# SOLR Search Demo

Angular app demonstrating SOLR search integration.

## Setup

### Start SOLR
```bash
cd solr-9.x.x
bin/solr start
bin/solr create -c aem_content
bin/solr post -c aem_content example/exampledocs/books.json
```

### Start Angular
```bash
npm install
ng serve
```

Open http://localhost:4200 and search for "electronics" or "memory".

## Tech Stack
- Angular 21
- Apache SOLR 9.x
- TypeScript
