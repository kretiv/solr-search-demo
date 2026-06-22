package com.example.solr.indexer;

import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationActionType;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Listens for AEM replication events and keeps the SOLR index in sync.
 *
 * Interview talking points:
 *   - Why EventHandler not a replication agent transport?
 *     An EventHandler runs inside the author JVM and reacts to the OSGi event
 *     published by the Replication service.  A transport hook runs on publish
 *     and requires a custom replication agent config — heavier to set up.
 *   - Event topic "com/day/cq/replication" fires for ACTIVATE, DEACTIVATE,
 *     DELETE, and TEST actions, so we check the type before doing anything.
 *   - Failure modes: SOLR unreachable → catch/log, bundle not active → AEM
 *     simply won't call handleEvent, service-user missing → LoginException.
 */
@Component(
    service = EventHandler.class,
    property = {
        // EventConstants.EVENT_TOPIC == "event.topics" — this tells the OSGi
        // EventAdmin which topic to deliver to this handler.
        EventConstants.EVENT_TOPIC + "=com/day/cq/replication"
    }
)
public class SolrReplicationEventHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SolrReplicationEventHandler.class);

    private static final String SOLR_UPDATE_URL =
        "http://localhost:8983/solr/aem_content/update?commit=true";

    // Subservice name — must match the mapping in Sling Service User Mapper.
    // See README for the one-time CRX setup required to make this work.
    private static final String SUBSERVICE = "solr-indexer";

    // ResourceResolverFactory is an OSGi service provided by Sling.
    // @Reference tells the DS runtime to inject it when the bundle activates.
    @Reference
    private ResourceResolverFactory resolverFactory;


    @Override
    public void handleEvent(Event event) {
        // ReplicationAction is AEM's helper that unwraps the raw OSGi event
        // properties into a typed object (path, action type, user, etc.).
        ReplicationAction action = ReplicationAction.fromEvent(event);
        if (action == null) {
            return;
        }

        String path = action.getPath();
        ReplicationActionType type = action.getType();

        LOG.debug("Replication event received: type={} path={}", type, path);

        if (ReplicationActionType.ACTIVATE.equals(type)) {
            indexPage(path);
        } else if (ReplicationActionType.DEACTIVATE.equals(type)) {
            deletePage(path);
        }
        // Ignore TEST and DELETE action types — not relevant for this demo.
    }

    // -------------------------------------------------------------------------
    // Index (add/update)
    // -------------------------------------------------------------------------

    private void indexPage(String path) {
        Map<String, Object> authInfo =
            Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE);

        // try-with-resources ensures the ResourceResolver is closed even on error.
        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {

            // AEM stores page metadata in the jcr:content child node.
            Resource content = resolver.getResource(path + "/jcr:content");
            if (content == null) {
                // Not a page (could be an asset, tag, etc.) — skip silently.
                LOG.warn("No jcr:content at {}, skipping SOLR index", path);
                return;
            }

            ValueMap props = content.getValueMap();
            String title        = props.get("jcr:title",        String.class);
            String resourceType = props.get("sling:resourceType", String.class);
            String description  = props.get("jcr:description",  String.class);

            // SOLR JSON update format: an array of documents to add/update.
            // The "id" field is SOLR's unique key — using the JCR path is
            // convenient because it is already unique and human-readable.
            String json = buildAddDocument(path, title, resourceType, description);
            postToSolr(json);

            LOG.info("SOLR indexed: {}", path);

        } catch (LoginException e) {
            // This means the service user 'solr-indexer' is not set up correctly.
            // See README for the two-step CRX/OSGi config required.
            LOG.error("Cannot obtain service resource resolver — check service-user mapping", e);
        }
    }

    private String buildAddDocument(String id, String title,
                                    String resourceType, String description) {
        return String.format(
            "[{\"id\":\"%s\",\"title\":\"%s\",\"resourceType\":\"%s\",\"description\":\"%s\"}]",
            escape(id),
            escape(title),
            escape(resourceType),
            escape(description)
        );
    }

    // -------------------------------------------------------------------------
    // Delete (deactivate / unpublish)
    // -------------------------------------------------------------------------

    private void deletePage(String path) {
        // SOLR JSON delete-by-id format.  We don't need a ResourceResolver here
        // because we only need the path (id), which the event already gave us.
        String json = String.format("{\"delete\":{\"id\":\"%s\"}}", escape(path));
        postToSolr(json);
        LOG.info("SOLR deleted: {}", path);
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private void postToSolr(String json) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(SOLR_UPDATE_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            java.io.InputStream stream = status == 200 ? conn.getInputStream() : conn.getErrorStream();
            String body;
            try (java.util.Scanner scanner = new java.util.Scanner(stream, "UTF-8").useDelimiter("\\A")) {
                body = scanner.hasNext() ? scanner.next() : "";
            }
            conn.disconnect();

            if (status == 200) {
                LOG.info("SOLR response: {}", body);
            } else {
                LOG.error("SOLR returned HTTP {}: {}", status, body);
            }

        } catch (Exception e) {
            // SOLR is down or unreachable — log and move on.
            LOG.error("Failed to reach SOLR at {}", SOLR_UPDATE_URL, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Minimal JSON-string escaping — only backslash and double-quote matter here. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
