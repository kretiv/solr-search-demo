package com.example.solr.indexer;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Proxies /bin/solr/* → http://localhost:8983/solr/* so the Angular app
 * can query SOLR from an AEM page without CORS issues.
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/solr",
        "sling.servlet.extensions=select",
        "sling.servlet.methods=GET"
    }
)
public class SolrProxyServlet extends SlingSafeMethodsServlet {

    private static final String SOLR_SELECT =
        "http://localhost:8983/solr/aem_content/select";

    @Override
    protected void doGet(SlingHttpServletRequest request,
                         SlingHttpServletResponse response)
            throws ServletException, IOException {

        String query  = request.getQueryString();
        String target = SOLR_SELECT + (query != null ? "?" + query : "");

        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        int status = conn.getResponseCode();
        response.setStatus(status);
        response.setContentType(conn.getContentType());

        InputStream in = status == 200 ? conn.getInputStream() : conn.getErrorStream();
        try (OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        } finally {
            conn.disconnect();
        }
    }
}
