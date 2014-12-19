/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.documentation;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Trinity;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.PsiElement;
import com.intellij.util.SystemProperties;
import com.intellij.util.ThrowableConsumer;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

public abstract class AbstractExternalFilter {
  private static final Logger LOG = Logger.getInstance(AbstractExternalFilter.class);

  private static final boolean EXTRACT_IMAGES_FROM_JARS = SystemProperties.getBooleanProperty("extract.doc.images", true);

  @NotNull
  @NonNls
  public static final String QUICK_DOC_DIR_NAME = "quickdoc";

  private static final Pattern ourClassDataStartPattern = Pattern.compile("START OF CLASS DATA", Pattern.CASE_INSENSITIVE);
  private static final Pattern ourClassDataEndPattern = Pattern.compile("SUMMARY ========", Pattern.CASE_INSENSITIVE);
  private static final Pattern ourNonClassDataEndPattern = Pattern.compile("<A NAME=", Pattern.CASE_INSENSITIVE);

  @NonNls
  protected static final Pattern ourAnchorSuffix = Pattern.compile("#(.*)$");
  protected static @NonNls final Pattern ourHtmlFileSuffix = Pattern.compile("/([^/]*[.][hH][tT][mM][lL]?)$");
  private static @NonNls final Pattern ourAnnihilator = Pattern.compile("/[^/^.]*/[.][.]/");
  private static @NonNls final Pattern ourImgSelector =
    Pattern.compile("<IMG[ \\t\\n\\r\\f]+SRC=\"([^>]*?)\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static @NonNls final Pattern ourPathInsideJarPattern = Pattern.compile(
    String.format("%s(.+\\.jar)!/(.+?)[^/]+", JarFileSystem.PROTOCOL_PREFIX),
    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
  );
  private static @NonNls final String JAR_PROTOCOL = "jar:";
  @NonNls private static final String HR = "<HR>";
  @NonNls private static final String P = "<P>";
  @NonNls private static final String DL = "<DL>";
  @NonNls protected static final String H2 = "</H2>";
  @NonNls protected static final String HTML_CLOSE = "</HTML>";
  @NonNls protected static final String HTML = "<HTML>";
  @NonNls private static final String BR = "<BR>";
  @NonNls private static final String DT = "<DT>";
  private static final Pattern CHARSET_META_PATTERN =
    Pattern.compile("<meta[^>]+\\s*charset=\"?([\\w\\-]*)\\s*\">", Pattern.CASE_INSENSITIVE);
  private static final String FIELD_SUMMARY = "<!-- =========== FIELD SUMMARY =========== -->";
  private static final String CLASS_SUMMARY = "<div class=\"summary\">";

  protected static abstract class RefConvertor {
    @NotNull
    private final Pattern mySelector;

    public RefConvertor(@NotNull Pattern selector) {
      mySelector = selector;
    }

    protected abstract String convertReference(String root, String href);

    public String refFilter(final String root, String read) {
      String toMatch = StringUtil.toUpperCase(read);
      StringBuilder ready = new StringBuilder();
      int prev = 0;
      Matcher matcher = mySelector.matcher(toMatch);

      while (matcher.find()) {
        String before = read.substring(prev, matcher.start(1) - 1);     // Before reference
        final String href = read.substring(matcher.start(1), matcher.end(1)); // The URL
        prev = matcher.end(1) + 1;
        ready.append(before);
        ready.append("\"");
        ready.append(ApplicationManager.getApplication().runReadAction(
          new Computable<String>() {
            @Override
            public String compute() {
              return convertReference(root, href);
            }
          }
        ));
        ready.append("\"");
      }

      ready.append(read.substring(prev, read.length()));

      return ready.toString();
    }
  }

  protected final RefConvertor myIMGConvertor = new RefConvertor(ourImgSelector) {
    @Override
    protected String convertReference(String root, String href) {
      if (StringUtil.startsWithChar(href, '#')) {
        return DocumentationManagerProtocol.DOC_ELEMENT_PROTOCOL + root + href;
      }

      String protocol = VirtualFileManager.extractProtocol(root);
      if (EXTRACT_IMAGES_FROM_JARS && Comparing.strEqual(protocol, JarFileSystem.PROTOCOL)) {
        Matcher matcher = ourPathInsideJarPattern.matcher(root);
        if (matcher.matches()) {
          // There is a possible case that javadoc jar is assembled with images inside. However, our standard quick doc
          // renderer (JEditorPane) doesn't know how to reference images from such jars. That's why we unpack them to temp
          // directory if necessary and substitute that 'inside jar path' to usual file url.
          String jarPath = matcher.group(1);
          String jarName = jarPath;
          int i = jarName.lastIndexOf(File.separatorChar);
          if (i >= 0 && i < jarName.length() - 1) {
            jarName = jarName.substring(i + 1);
          }
          jarName = jarName.substring(0, jarName.length() - ".jar".length());
          String basePath = matcher.group(2);
          String imgPath = FileUtil.toCanonicalPath(basePath + href);
          File unpackedImagesRoot = new File(FileUtilRt.getTempDirectory(), QUICK_DOC_DIR_NAME);
          File unpackedJarImagesRoot = new File(unpackedImagesRoot, jarName);
          File unpackedImage = new File(unpackedJarImagesRoot, imgPath);
          boolean referenceUnpackedImage = true;
          if (!unpackedImage.isFile()) {
            referenceUnpackedImage = false;
            try {
              JarFile jarFile = new JarFile(jarPath);
              try {
                ZipEntry entry = jarFile.getEntry(imgPath);
                if (entry != null) {
                  FileUtilRt.createIfNotExists(unpackedImage);
                  FileOutputStream fOut = new FileOutputStream(unpackedImage);
                  try {
                    // Don't bother with wrapping file output stream into buffered stream in assumption that FileUtil operates
                    // on NIO channels.
                    FileUtilRt.copy(jarFile.getInputStream(entry), fOut);
                    referenceUnpackedImage = true;
                  }
                  finally {
                    fOut.close();
                  }
                }
                unpackedImage.deleteOnExit();
              }
              finally {
                jarFile.close();
              }
            }
            catch (IOException e) {
              LOG.debug(e);
            }
          }
          if (referenceUnpackedImage) {
            return LocalFileSystem.PROTOCOL_PREFIX + unpackedImage.getAbsolutePath();
          }
        }
      }

      if (Comparing.strEqual(protocol, LocalFileSystem.PROTOCOL)) {
        final String path = VirtualFileManager.extractPath(root);
        if (!path.startsWith("/")) {//skip host for local file system files (format - file://host_name/path)
          root = VirtualFileManager.constructUrl(LocalFileSystem.PROTOCOL, "/" + path);
        }
      }
      return ourHtmlFileSuffix.matcher(root).replaceAll("/") + href;
    }
  };

  protected static String doAnnihilate(String path) {
    int len = path.length();

    do {
      path = ourAnnihilator.matcher(path).replaceAll("/");
    }
    while (len > (len = path.length()));

    return path;
  }

  public String correctRefs(String root, String read) {
    String result = read;

    for (RefConvertor myReferenceConvertor : getRefConverters()) {
      result = myReferenceConvertor.refFilter(root, result);
    }

    return result;
  }

  protected abstract RefConvertor[] getRefConverters();

  private static void getReaderByUrl(@NotNull String url, final @NotNull ThrowableConsumer<Reader, IOException> consumer)
    throws IOException {
    if (url.startsWith(JAR_PROTOCOL)) {
      VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(BrowserUtil.getDocURL(url));
      if (file != null) {
        consumer.consume(new StringReader(VfsUtilCore.loadText(file)));
      }
      return;
    }

    final URL parsedUrl = BrowserUtil.getURL(url);
    if (parsedUrl == null) {
      return;
    }

    HttpRequests.request(parsedUrl.toString()).connect(new HttpRequests.RequestProcessor<Void>() {
      @Override
      public Void process(@NotNull HttpRequests.Request request) throws IOException {
        String contentEncoding = guessEncoding(parsedUrl);
        InputStream inputStream = request.getInputStream();
        //noinspection IOResourceOpenedButNotSafelyClosed
        consumer.consume(contentEncoding != null ? new MyReader(inputStream, contentEncoding) : new MyReader(inputStream));
        return null;
      }
    });
  }

  private static String guessEncoding(URL url) {
    String result = null;
    BufferedReader reader = null;
    try {
      URLConnection connection = url.openConnection();
      result = connection.getContentEncoding();
      if (result != null) return result;
      //noinspection IOResourceOpenedButNotSafelyClosed
      reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
      for (String htmlLine = reader.readLine(); htmlLine != null; htmlLine = reader.readLine()) {
        result = parseContentEncoding(htmlLine);
        if (result != null) {
          break;
        }
      }
    }
    catch (IOException ignored) {
    }
    finally {
      if (reader != null) {
        try {
          reader.close();
        }
        catch (IOException ignored) {
        }
      }
    }
    return result;
  }

  @Nullable
  @SuppressWarnings({"HardCodedStringLiteral"})
  public String getExternalDocInfo(final String url) throws Exception {
    Application app = ApplicationManager.getApplication();
    if (!app.isUnitTestMode() && app.isDispatchThread() || app.isWriteAccessAllowed()) {
      LOG.error("May block indefinitely: shouldn't be called from EDT or under write lock");
      return null;
    }

    if (url == null) {
      return null;
    }
    if (MyJavadocFetcher.isFree()) {
      final MyJavadocFetcher fetcher = new MyJavadocFetcher(url, new MyDocBuilder() {
        @Override
        public void buildFromStream(String url, Reader input, StringBuilder result) throws IOException {
          doBuildFromStream(url, input, result);
        }
      });
      final Future<?> fetcherFuture = app.executeOnPooledThread(fetcher);
      try {
        fetcherFuture.get();
      }
      catch (Exception e) {
        return null;
      }
      final Exception exception = fetcher.getException();
      if (exception != null) {
        fetcher.cleanup();
        throw exception;
      }

      final String docText = correctRefs(ourAnchorSuffix.matcher(url).replaceAll(""), fetcher.getData());
      if (LOG.isDebugEnabled()) {
        LOG.debug("Filtered JavaDoc: " + docText + "\n");
      }
      return PlatformDocumentationUtil.fixupText(docText);
    }
    return null;
  }

  @Nullable
  public String getExternalDocInfoForElement(final String docURL, final PsiElement element) throws Exception {
    return getExternalDocInfo(docURL);
  }

  protected void doBuildFromStream(String url, Reader input, StringBuilder data) throws IOException {
    doBuildFromStream(url, input, data, true);
  }

  protected void doBuildFromStream(final String url, Reader input, final StringBuilder data, boolean search4Encoding) throws IOException {
    Trinity<Pattern, Pattern, Boolean> settings = getParseSettings(url);
    @NonNls Pattern startSection = settings.first;
    @NonNls Pattern endSection = settings.second;
    boolean useDt = settings.third;
    @NonNls String greatestEndSection = "<!-- ========= END OF CLASS DATA ========= -->";

    data.append(HTML);
    data.append("<style type=\"text/css\">" +
                "  ul.inheritance {\n" +
                "      margin:0;\n" +
                "      padding:0;\n" +
                "  }\n" +
                "  ul.inheritance li {\n" +
                "       display:inline;\n" +
                "       list-style:none;\n" +
                "  }\n" +
                "  ul.inheritance li ul.inheritance {\n" +
                "    margin-left:15px;\n" +
                "    padding-left:15px;\n" +
                "    padding-top:1px;\n" +
                "  }\n" +
                "</style>");

    String read;
    String contentEncoding = null;
    @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
    BufferedReader buf = new BufferedReader(input);
    do {
      read = buf.readLine();
      if (read != null && search4Encoding && read.contains("charset")) {
        String foundEncoding = parseContentEncoding(read);
        if (foundEncoding != null) {
          contentEncoding = foundEncoding;
        }
      }
    }
    while (read != null && !startSection.matcher(StringUtil.toUpperCase(read)).find());

    if (input instanceof MyReader && contentEncoding != null && !contentEncoding.equalsIgnoreCase(CharsetToolkit.UTF8) &&
        !contentEncoding.equals(((MyReader)input).getEncoding())) {
      //restart page parsing with correct encoding
      try {
        final String finalContentEncoding = contentEncoding;
        getReaderByUrl(url, new ThrowableConsumer<Reader, IOException>() {
          @Override
          public void consume(Reader reader) throws IOException {
            data.delete(0, data.length());
            doBuildFromStream(url, new MyReader(((MyReader)reader).getInputStream(), finalContentEncoding), data, false);
          }
        });
      }
      catch (ProcessCanceledException e) {
        return;
      }
      return;
    }

    if (read == null) {
      data.delete(0, data.length());
      return;
    }

    if (useDt) {
      boolean skip = false;

      do {
        if (StringUtil.toUpperCase(read).contains(H2) && !read.toUpperCase(Locale.ENGLISH).contains("H2")) { // read=class name in <H2>
          data.append(H2);
          skip = true;
        }
        else if (endSection.matcher(read).find() || StringUtil.indexOfIgnoreCase(read, greatestEndSection, 0) != -1) {
          data.append(HTML_CLOSE);
          return;
        }
        else if (!skip) {
          appendLine(data, read);
        }
      }
      while (((read = buf.readLine()) != null) && !StringUtil.toUpperCase(read).trim().equals(DL) &&
             !StringUtil.containsIgnoreCase(read, "<div class=\"description\""));

      data.append(DL);

      StringBuilder classDetails = new StringBuilder();
      while (((read = buf.readLine()) != null) && !StringUtil.toUpperCase(read).equals(HR) && !StringUtil.toUpperCase(read).equals(P)) {
        if (reachTheEnd(data, read, classDetails)) return;
        appendLine(classDetails, read);
      }

      while (((read = buf.readLine()) != null) && !StringUtil.toUpperCase(read).equals(P) && !StringUtil.toUpperCase(read).equals(HR)) {
        if (reachTheEnd(data, read, classDetails)) return;
        appendLine(data, read.replaceAll(DT, DT + BR));
      }

      data.append(classDetails);
      data.append(P);
    }
    else {
      appendLine(data, read);
    }

    while (((read = buf.readLine()) != null) &&
           !endSection.matcher(read).find() &&
           StringUtil.indexOfIgnoreCase(read, greatestEndSection, 0) == -1) {
      if (!StringUtil.toUpperCase(read).contains(HR)
          && !StringUtil.containsIgnoreCase(read, "<ul class=\"blockList\">")
          && !StringUtil.containsIgnoreCase(read, "<li class=\"blockList\">")) {
        appendLine(data, read);
      }
    }

    data.append(HTML_CLOSE);
  }

  /**
   * Decides what settings should be used for parsing content represented by the given url.
   *
   * @param url url which points to the target content
   * @return following data: (start interested data boundary pattern; end interested data boundary pattern;
   * replace table data by &lt;dt&gt;)
   */
  @NotNull
  protected Trinity<Pattern, Pattern, Boolean> getParseSettings(@NotNull String url) {
    Pattern startSection = ourClassDataStartPattern;
    Pattern endSection = ourClassDataEndPattern;
    boolean useDt = true;

    Matcher anchorMatcher = ourAnchorSuffix.matcher(url);
    if (anchorMatcher.find()) {
      useDt = false;
      startSection = Pattern.compile(Pattern.quote("<a name=\"" + anchorMatcher.group(1) + "\""), Pattern.CASE_INSENSITIVE);
      endSection = ourNonClassDataEndPattern;
    }
    return Trinity.create(startSection, endSection, useDt);
  }

  private static boolean reachTheEnd(StringBuilder data, String read, StringBuilder classDetails) {
    if (StringUtil.indexOfIgnoreCase(read, FIELD_SUMMARY, 0) != -1 ||
        StringUtil.indexOfIgnoreCase(read, CLASS_SUMMARY, 0) != -1) {
      data.append(classDetails);
      data.append(HTML_CLOSE);
      return true;
    }
    return false;
  }

  @Nullable
  static String parseContentEncoding(@NotNull String htmlLine) {
    if (!htmlLine.contains("charset")) {
      return null;
    }
    final Matcher matcher = CHARSET_META_PATTERN.matcher(htmlLine);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return null;
  }

  private static void appendLine(StringBuilder buffer, final String read) {
    buffer.append(read);
    buffer.append("\n");
  }

  private interface MyDocBuilder {
    void buildFromStream(String url, Reader input, StringBuilder result) throws IOException;
  }

  private static class MyJavadocFetcher implements Runnable {
    private static boolean ourFree = true;
    private final StringBuilder data = new StringBuilder();
    private final String url;
    private final MyDocBuilder myBuilder;
    private Exception myException;

    public MyJavadocFetcher(String url, MyDocBuilder builder) {
      this.url = url;
      myBuilder = builder;
      //noinspection AssignmentToStaticFieldFromInstanceMethod
      ourFree = false;
    }

    public static boolean isFree() {
      return ourFree;
    }

    public String getData() {
      return data.toString();
    }

    @Override
    public void run() {
      try {
        if (url == null) {
          return;
        }

        try {
          getReaderByUrl(url, new ThrowableConsumer<Reader, IOException>() {
            @Override
            public void consume(Reader reader) throws IOException {
              myBuilder.buildFromStream(url, reader, data);
            }
          });
        }
        catch (ProcessCanceledException ignored) {
        }
        catch (IOException e) {
          myException = e;
        }
      }
      finally {
        //noinspection AssignmentToStaticFieldFromInstanceMethod
        ourFree = true;
      }
    }

    public Exception getException() {
      return myException;
    }

    public void cleanup() {
      myException = null;
    }
  }

  private static class MyReader extends InputStreamReader {
    private InputStream myInputStream;

    public MyReader(InputStream in) {
      super(in);
      myInputStream = in;
    }

    public MyReader(InputStream in, String charsetName) throws UnsupportedEncodingException {
      super(in, charsetName);
      myInputStream = in;
    }

    public InputStream getInputStream() {
      return myInputStream;
    }
  }
}
