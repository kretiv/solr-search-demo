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



# Task: Build an AEM-to-SOLR indexing service (OSGi EventHandler)

Context


Local AEM 6.5 instance running at http://localhost:4502 (author)
Local Apache SOLR 9.x running at http://localhost:8983
SOLR core name: aem_content
AEM project follows standard Maven multi-module structure (core / ui.apps / ui.content)
Goal: build a small, demoable proof of concept showing how AEM content gets
pushed into SOLR automatically when content is replicated/published — not
a production-grade system, just something I can run locally and explain
in a job interview.


What to build

An OSGi EventHandler service in the core module that:


Listens for AEM replication events (topic: com/day/cq/replication)
When a page is activated/published, reads the resource at the event's path
Extracts a small set of properties from the resource's jcr:content node:

jcr:title → map to title
the resource path itself → map to id
sling:resourceType → map to resourceType
jcr:description if present → map to description



Builds a SOLR document (JSON) from those fields
POSTs it to http://localhost:8983/solr/aem_content/update?commit=true
using a simple HTTP client (java.net.http.HttpClient is fine — no need
for SolrJ unless it's already a dependency)
Logs success/failure clearly so I can watch it work in error.log


Acceptance criteria (what "done" looks like)


I can publish/activate a page in my local AEM author instance
Within a few seconds, a new or updated document appears in SOLR for that
page when I query http://localhost:8983/solr/aem_content/select?q=:
The service is visible and shows "Active" in
http://localhost:4502/system/console/components
I can explain every part of this in an interview: the event topic, why
EventHandler vs replication agent transport, the document shape, and the
failure modes (SOLR down, AEM bundle not active, network issue)


Constraints / preferences


Keep it simple — this is for interview demo purposes, not production
Don't worry about retry logic, queuing, or error recovery robustness —
a basic try/catch with logging is enough
Prefer code I can read and explain line-by-line over clever abstractions
If something about my local AEM project structure doesn't match assumptions
above, inspect the actual project files first and adapt to what's really there


Stretch goal (only if the above works easily)

Also handle deactivation/unpublish events by deleting the corresponding
document from the SOLR index (/update with a delete payload by id).

---

# AEM-to-SOLR Indexing Service (`aem-indexer/`)

An OSGi EventHandler that listens for AEM replication events and pushes page content into the SOLR `aem_content` core automatically when a page is published or unpublished.

## How it works

1. Author publishes a page → AEM fires an OSGi event on topic `com/day/cq/replication`
2. `SolrReplicationEventHandler.handleEvent()` receives the event
3. For ACTIVATE: reads `jcr:content` via Sling API, builds a JSON document, POSTs it to SOLR's `/update` endpoint
4. For DEACTIVATE: sends a JSON delete-by-id to SOLR
5. `?commit=true` on every POST so documents are immediately queryable

## One-time AEM setup (service user)

The handler uses a Sling *service resource resolver* (not the deprecated admin resolver).
Do this once in your local AEM author instance:

**Step 1 — Create a system user**
1. Go to `http://localhost:4502/crx/explorer` → click **User Administration**
2. Click **Create System User**
3. Set User ID to `solr-indexer` → Save

**Step 2 — Grant read permission on /content**
1. Go to `http://localhost:4502/useradmin` (or CRX/DE → `/home/users/system/solr-indexer`)
2. Add `jcr:read` privilege on `/content`

**Step 3 — Map the subservice in the OSGi console**
1. Go to `http://localhost:4502/system/console/configMgr`
2. Find **Apache Sling Service User Mapper Service Amendment**
3. Add the mapping:
   ```
   com.example.solr.aem-solr-indexer:solr-indexer=solr-indexer
   ```
   Format: `<Bundle-SymbolicName>:<subservice-name>=<system-user-id>`

## Build and deploy

```bash
cd aem-indexer
mvn clean package
```

This produces `target/aem-solr-indexer-1.0-SNAPSHOT.jar`.

**Install via AEM Felix console (easiest for a demo):**
1. Go to `http://localhost:4502/system/console/bundles`
2. Click **Install/Update** → upload the jar → tick **Start Bundle** → Install

**Or install via curl:**
```bash
curl -u admin:admin -F action=install \
  -F bundlestartlevel=20 \
  -F bundlefile=@target/aem-solr-indexer-1.0-SNAPSHOT.jar \
  http://localhost:4502/system/console/bundles
```

## Verify it is running

Go to `http://localhost:4502/system/console/components` and search for `SolrReplicationEventHandler` — status should show **Active**.

## Test the full flow

1. Open `http://localhost:4502` → create or open any page under `/content`
2. Publish the page (Quick Publish or Manage Publication → Publish)
3. Watch `crx-quickstart/logs/error.log` for:
   ```
   INFO  ...SolrReplicationEventHandler - SOLR indexed: /content/mysite/en/mypage
   ```
4. Query SOLR to confirm the document arrived:
   ```
   http://localhost:8983/solr/aem_content/select?q=*:*&wt=json
   ```

## Tech stack

| Layer | Technology |
|---|---|
| Event hook | OSGi EventHandler (`com/day/cq/replication` topic) |
| AEM API | Sling ResourceResolver + ValueMap, CQ ReplicationAction |
| HTTP client | `java.net.HttpURLConnection` (Java 8, no extra dependency) |
| Build | Maven + maven-bundle-plugin (Felix BND) |

---

# Next Steps: Embedding the Angular Search UI in an AEM Page

The Angular app currently runs standalone on `localhost:4200`. To surface it inside an actual AEM page (without SPA Editor), use AEM's **ClientLib** mechanism.

## Why not SPA Editor or Content Fragments?

- **SPA Editor** — maps AEM components to Angular components for in-context authoring. Overkill here: the search widget has no author-editable regions.
- **Content Fragments** — structured data for authoring; not a UI host. Not applicable.
- **ClientLib embed** — the right fit. Bundle the Angular build output as a ClientLib, reference it from a simple AEM component, and the app boots inside any AEM page.

## Steps

### 1. Build the Angular app
```bash
ng build --configuration production
```
Output lands in `dist/solr-search-demo/browser/`.

### 2. Create an AEM ClientLib node
In CRX/DE (`http://localhost:4502/crx/de`), create the following structure:

```
/apps/solr-search/clientlibs/
  searchapp/
    jcr:primaryType  = cq:ClientLibraryFolder
    categories       = [solr.search.app]
    js.txt           (lists the Angular bundle JS files in order)
    css.txt          (lists the Angular styles CSS file)
    (copy the files from dist/ here)
```

`js.txt` example:
```
#base=.
main.js
polyfills.js
```

### 3. Create an AEM component
Create a minimal component at `/apps/solr-search/components/search-page/search-page.html`:
```html
<div id="app-root"></div>
<sly data-sly-use.clientlib="/libs/granite/sightly/templates/clientlib.html"
     data-sly-call="${clientlib.all @ categories='solr.search.app'}"/>
```

### 4. Create a page using that component
1. In AEM Sites, create a page using a template that includes `search-page` component
2. Open the page — Angular boots inside the `<div id="app-root">` and the search box appears

### 5. SOLR URL for publish
Replace the proxy with the real SOLR URL in `environment.prod.ts` for production builds, or configure CORS on the SOLR instance for the publish domain.