// Copyright 2013 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.fs;

import com.google.common.annotations.VisibleForTesting;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdEncoder;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

class HtmlResponseWriter implements Closeable {
  private static final Logger log
      = Logger.getLogger(HtmlResponseWriter.class.getName());

  private enum State {
    /** Initial state after construction. */
    INITIAL,
    /**
     * {@link #start} was just called, so the HTML header is in place, but no
     * other content.
     */
    STARTED,
    /** {@link #startSection} has been called, so we are within a section. */
    //IN_SECTION,
    /** {@link #finish} has been called, so the HTML footer has been written. */
    FINISHED,
    /** The writer has been closed. */
    CLOSED,
  }

  public enum ObjectType {
    DOCUMENT,
    FOLDER,
  }

  private final Writer writer;
  private final DocIdEncoder docIdEncoder;
  private final Locale locale;
  private DocId docId;
  private URI docUri;
  private State state = State.INITIAL;

  public HtmlResponseWriter(Writer writer, DocIdEncoder docIdEncoder,
      Locale locale) {
    if (writer == null) {
      throw new NullPointerException();
    }
    if (docIdEncoder == null) {
      throw new NullPointerException();
    }
    if (locale == null) {
      throw new NullPointerException();
    }
    this.writer = writer;
    this.docIdEncoder = docIdEncoder;
    this.locale = locale;
  }

  /**
   * Start writing HTML document.
   *
   * @param docId the DocId for the document being written out
   * @param type type of document referred to by {@code docId}
   * @param label possibly-{@code null} title or name of {@code docId}
   */
  public void start(DocId docId, ObjectType type, String label)
      throws IOException {
    if (state != State.INITIAL) {
      throw new IllegalStateException("In unexpected state: " + state);
    }
    this.docId = docId;
    this.docUri = docIdEncoder.encodeDocId(docId);
    // TODO(ejona): Localize.
    String header = MessageFormat.format("{0} {1}",
        computeTypeHeaderLabel(type), computeLabel(label, docId));
    writer.write("<!DOCTYPE html>\n<html><head><title>");
    writer.write(escapeContent(header));
    writer.write("</title></head><body><h1>");
    writer.write(escapeContent(header));
    writer.write("</h1>");
    state = State.STARTED;
  }

/*
  public void startSection(ObjectType type) throws IOException {
    if (state != State.STARTED && state != State.IN_SECTION) {
      throw new IllegalStateException("In unexpected state: " + state);
    }
    checkAndCloseSection();
    writer.write("<p>");
    writer.write(escapeContent(computeTypeSectionLabel(type)));
    writer.write("</p><ul>");
    state = State.IN_SECTION;
  }

  private void checkAndCloseSection() throws IOException {
    if (state == State.IN_SECTION) {
      writer.write("</ul>");
    }
  }
*/

  /**
   * @param docId docId to add as a link in the document
   * @param label possibly-{@code null} title or description of {@code docId}
   */
  public void addLink(DocId doc, String label) throws IOException {
//    if (state != State.IN_SECTION) {
    if (state != State.STARTED) {
      throw new IllegalStateException("In unexpected state: " + state);
    }
    if (doc == null) {
      throw new NullPointerException();
    }
    writer.write("<li><a href=\"");
    writer.write(escapeAttributeValue(encodeDocId(doc)));
    writer.write("\">");
    writer.write(escapeContent(computeLabel(label, doc)));
    writer.write("</a></li>");
  }

  /**
   * Complete HTML body and flush.
   */
  public void finish() throws IOException {
    log.entering("HtmlResponseWriter", "finish");
//    if (state != State.STARTED && state != State.IN_SECTION) {
    if (state != State.STARTED) {
      throw new IllegalStateException("In unexpected state: " + state);
    }
    //checkAndCloseSection();
    writer.write("</body></html>");
    writer.flush();
    state = State.FINISHED;
    log.exiting("HtmlResponseWriter", "finish");
  }

  /**
   * Close underlying writer. You will generally want to call {@link #finish}
   * first.
   */
  @Override
  public void close() throws IOException {
    log.entering("HtmlResponseWriter", "close");
    writer.close();
    state = State.CLOSED;
    log.exiting("HtmlResponseWriter", "close");
  }

  /**
   * Encodes a DocId into a URI formatted as a string.
   */
  private String encodeDocId(DocId doc) {
    log.entering("HtmlResponseWriter", "encodeDocId", doc);
    URI uri = docIdEncoder.encodeDocId(doc);
    uri = relativize(docUri, uri);
    String encoded = uri.toASCIIString();
    log.exiting("HtmlResponseWriter", "encodeDocId", encoded);
    return encoded;
  }

  /**
   * Produce a relative URI from {@code uri} relative to {@code base}, assuming
   * both URIs are hierarchial. If possible, a relative URI will be returned
   * that can be resolved from {@code base}, otherwise {@code uri} will be
   * returned.
   *
   * <p>Necessary since {@link URI#relativize} is broken when considering
   * http://host/path vs http://host/path/ as the base URI. See
   * <a href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6226081">
   * Bug 6226081</a> for more information. In addition, this version uses
   * {@code ..} when possible unlike {@link URI#relativize}.
   */
  @VisibleForTesting
  static URI relativize(URI base, URI uri) {
    if (base.getScheme() == null || !base.getScheme().equals(uri.getScheme())
        || base.getAuthority() == null
        || !base.getAuthority().equals(uri.getAuthority())) {
      return uri;
    }
    if (base.equals(uri)) {
      return URI.create("#");
    }
    // These paths are known to start with a / or be the empty string; since the
    // URIs have a scheme, we know they are absolute.
    String basePath = base.getPath();
    String uriPath = uri.getPath();

    String[] basePathParts = basePath.split("/", -1);
    String[] uriPathParts = uriPath.split("/", -1);
    int i = 0;
    // Remove common folders. Since we are looking at folders, we don't compare
    // the last elements in the array, because they were after the last '/' in
    // the URIs.
    for (; i < basePathParts.length - 1 && i < uriPathParts.length - 1; i++) {
      if (!basePathParts[i].equals(uriPathParts[i])) {
        break;
      }
    }
    StringBuilder pathBuilder = new StringBuilder();
    for (int j = i; j < basePathParts.length - 1; j++) {
      pathBuilder.append("../");
    }
    for (; i < uriPathParts.length; i++) {
      pathBuilder.append(uriPathParts[i]);
      pathBuilder.append("/");
    }
    String path = pathBuilder.substring(0, pathBuilder.length() - 1);
    int colonLocation = path.indexOf(":");
    int slashLocation = path.indexOf("/");
    if (colonLocation != -1
        && (slashLocation == -1 || colonLocation < slashLocation)) {
      // If there is a colon before the first slash, then it is easy to confuse
      // this relative URI for an absolute URI. Thus, we prepend a ./ so that
      // the beginning is obviously not a scheme.
      path = "./" + path;
    }
    try {
      return new URI(null, null, path, uri.getQuery(), uri.getFragment());
    } catch (URISyntaxException ex) {
      throw new AssertionError(ex);
    }
  }

  private String computeLabel(String label, DocId doc) {
    if (label == null || "".equals(label)) {
      // Use the last part of the URL if an item doesn't have a title. The last
      // part of the URL will generally be a filename in this case.
      String[] parts = doc.getUniqueId().split("/");
      label = parts[parts.length - 1];
    }
    return label;
  }

  private String computeTypeHeaderLabel(ObjectType type) {
    // TODO(ejona): Localize.
    switch (type) {
      case DOCUMENT:
        return "Document";
      case FOLDER:
        return "Folder";
      default:
        log.log(Level.WARNING, "Unexpected ObjectType: {0}", type);
        return "";
    }
  }

  private String computeTypeSectionLabel(ObjectType type) {
    // TODO(ejona): Localize.
    switch (type) {
      case DOCUMENT:
        return "Documents";
      case FOLDER:
        return "Folders";
      default:
        log.log(Level.WARNING, "Unexpected ObjectType: {0}", type);
        return "";
    }
  }

  private String escapeContent(String raw) {
    return raw.replace("&", "&amp;").replace("<", "&lt;");
  }

  private String escapeAttributeValue(String raw) {
    return escapeContent(raw).replace("\"", "&quot;").replace("'", "&apos;");
  }
}
