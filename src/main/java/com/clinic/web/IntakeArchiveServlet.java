package com.clinic.web;

import com.clinic.storage.DocumentStorage;

import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Serves the shared intake archive: the source files retained for each import
 * batch and the handoff packets the partner practices collect. The front desk
 * browses it from the import/export workspace.
 */
public class IntakeArchiveServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Inject
    private DocumentStorage storage;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        Path archive = storage.getBaseDirectory().resolve("imports");
        String pathInfo = request.getPathInfo();
        Path target = pathInfo == null || pathInfo.equals("/")
                ? archive
                : archive.resolve(pathInfo.substring(1));
        boolean listings = Boolean.parseBoolean(getServletConfig().getInitParameter("listings"));
        if (Files.isDirectory(target) && listings) {
            response.setContentType("text/html;charset=UTF-8");
            StringBuilder html = new StringBuilder(
                    "<!DOCTYPE html><html><body><h3>Intake archive</h3><ul>");
            String base = request.getContextPath() + request.getServletPath();
            try (Stream<Path> entries = Files.list(target)) {
                List<Path> files = entries.toList();
                for (Path file : files) {
                    html.append("<li><a href=\"").append(base).append('/')
                            .append(file.getFileName()).append("\">")
                            .append(file.getFileName()).append("</a></li>");
                }
            }
            html.append("</ul></body></html>");
            response.getWriter().write(html.toString());
            return;
        }
        if (!Files.isRegularFile(target)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("application/octet-stream");
        try (InputStream in = Files.newInputStream(target)) {
            OutputStream out = response.getOutputStream();
            in.transferTo(out);
        }
    }
}
