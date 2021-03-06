/*******************************************************************************
 * © [2013] LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.linkedin.pinot.common.utils;

import com.linkedin.pinot.common.Utils;
import com.linkedin.pinot.common.exception.PermanentDownloadException;
import com.linkedin.pinot.common.exception.PermanentPushFailureException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpVersion;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.EntityEnclosingMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.PartSource;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.protocol.HTTP;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FileUploadUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileUploadUtils.class);
  public static final String HDR_CONTROLLER_HOST = "Pinot-Controller-Host";
  public static final String HDR_CONTROLLER_VERSION = "Pinot-Controller-Version";
  public static final String NOT_IN_RESPONSE = "NotInResponse";
  private static final String SEGMENTS_PATH = "segments";
  public static final String UPLOAD_TYPE = "UPLOAD_TYPE";
  public static final String DOWNLOAD_URI = "DOWNLOAD_URI";
  public static final int MAX_RETRIES = 5;
  public static final int SLEEP_BETWEEN_RETRIES_IN_SECONDS = 60;
  private static final MultiThreadedHttpConnectionManager CONNECTION_MANAGER =
      new MultiThreadedHttpConnectionManager();
  private static final HttpClient FILE_UPLOAD_HTTP_CLIENT = new HttpClient(CONNECTION_MANAGER);

  {
    FILE_UPLOAD_HTTP_CLIENT.getParams().setParameter("http.protocol.version", HttpVersion.HTTP_1_1);
    FILE_UPLOAD_HTTP_CLIENT.getParams().setSoTimeout(3600 * 1000); // One hour
  }

  public enum SendFileMethod {
    POST {
      public EntityEnclosingMethod forUri(String uri) {
        return new PostMethod(uri);
      }
    },
    PUT {
      public EntityEnclosingMethod forUri(String uri) {
        return new PutMethod(uri);
      }
    };

    public abstract EntityEnclosingMethod forUri(String uri);
  }

  public static int sendFile(final String host, final String port, final String path, final String fileName,
      final InputStream inputStream, final long lengthInBytes, SendFileMethod httpMethod) {
    return sendFile("http://" + host + ":" + port + "/" + path, fileName, inputStream, lengthInBytes,
        httpMethod, new ArrayList<Header>());
  }

  /**
   * This method is useful only for segment push to the controller, as only controller will take the IF_MATCH header
   * @param uri
   * @param fileName
   * @param inputStream
   * @param lengthInBytes
   * @param httpMethod
   * @param headers Contains If-Match header with originalSegmentCrc and User-Agent header to tell the controller the request
   *                is coming from Minion
   * @return
   */
  public static int sendFile(String uri, final String fileName, final InputStream inputStream, final long lengthInBytes,
      SendFileMethod httpMethod, List<Header> headers) {
    EntityEnclosingMethod method = null;
    try {
      method = httpMethod.forUri(uri);
      Part[] parts = {new FilePart(fileName, new PartSource() {
        @Override
        public long getLength() {
          return lengthInBytes;
        }

        @Override
        public String getFileName() {
          return fileName;
        }

        @Override
        public InputStream createInputStream() throws IOException {
          return new BufferedInputStream(inputStream);
        }
      })};
      method.setRequestEntity(new MultipartRequestEntity(parts, new HttpMethodParams()));

      for (Header header: headers) {
        method.addRequestHeader(header);
      }

      FILE_UPLOAD_HTTP_CLIENT.executeMethod(method);
      if (method.getStatusCode() >= 400) {
        String errorString = "POST Status Code: " + method.getStatusCode() + "\n";
        if (method.getResponseHeader("Error") != null) {
          errorString += "ServletException: " + method.getResponseHeader("Error").getValue();
        }
        throw new HttpException(errorString);
      }
      return method.getStatusCode();
    } catch (Exception e) {
      LOGGER.error("Caught exception while sending file: {}", fileName, e);
      Utils.rethrowException(e);
      throw new AssertionError("Should not reach this");
    } finally {
      if (method != null) {
        method.releaseConnection();
      }
    }
  }

  public static int sendSegmentFile(final String host, final String port, final String fileName,
      File file, final long lengthInBytes) {
    return sendSegmentFile(host, port, fileName, file, lengthInBytes, MAX_RETRIES, SLEEP_BETWEEN_RETRIES_IN_SECONDS);
  }

  public static int sendSegmentFile(final String host, final String port, final String fileName,
      File file, final long lengthInBytes, int maxRetries, int sleepTimeSec) {
    for (int numRetries = 0; ; numRetries++) {
      try ( InputStream inputStream = new FileInputStream(file)) {
        return sendSegmentFile(host, port, fileName, inputStream, lengthInBytes);
      } catch (Exception e) {
        if (numRetries >= maxRetries) {
          throw new RuntimeException(e);
        }
        LOGGER.warn("Retry " + numRetries + " of Upload of File " + fileName + " to host " + host
            + " after error trying to send file ", e);
        try {
          Thread.sleep(sleepTimeSec * 1000);
        } catch (Exception e1) {
          LOGGER.error("Upload of File " + fileName + " to host " + host + " interrupted while waiting to retry after error");
          throw new RuntimeException(e1);
        }
      }
    }
  }

  public static int sendSegmentFile(final String host, final String port, final String fileName,
      final InputStream inputStream, final long lengthInBytes) {
    return sendFile(host, port, SEGMENTS_PATH, fileName, inputStream, lengthInBytes, SendFileMethod.POST);
  }

  public static int sendSegmentUri(final String host, final String port, final String uri) {
    return sendSegmentUri(host, port, uri, MAX_RETRIES, SLEEP_BETWEEN_RETRIES_IN_SECONDS);
  }

  public static int sendSegmentUri(final String host, final String port, final String uri, final int maxRetries,
      final int sleepTimeSec) {
    for (int numRetries = 0; ; numRetries++) {
      try {
        return sendSegmentUriImpl(host, port, uri);
      } catch (Exception e) {
        if (numRetries >= maxRetries) {
          Utils.rethrowException(e);
        }
        try {
          Thread.sleep(sleepTimeSec * 1000);
        } catch (Exception e1) {
          LOGGER.error("Upload of URI " + uri + " to host " + host + " interrupted while waiting to retry after error");
          Utils.rethrowException(e1);
        }
      }
    }
  }

  private static int sendSegmentUriImpl(final String host, final String port, final String uri) {
    SendFileMethod httpMethod = SendFileMethod.POST;
    EntityEnclosingMethod method = null;
    try {
      method = httpMethod.forUri("http://" + host + ":" + port + "/" + SEGMENTS_PATH);
      method.setRequestHeader(UPLOAD_TYPE, FileUploadType.URI.toString());
      method.setRequestHeader(DOWNLOAD_URI, uri);
      method.setRequestHeader(HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
      FILE_UPLOAD_HTTP_CLIENT.executeMethod(method);
      if (method.getStatusCode() >= 400) {
        String errorString = "POST Status Code: " + method.getStatusCode() + "\n";
        if (method.getResponseHeader("Error") != null) {
          errorString += "ServletException: " + method.getResponseHeader("Error").getValue();
        }
        throw new HttpException(errorString);
      }
      return method.getStatusCode();
    } catch (Exception e) {
      LOGGER.error("Caught exception while sending uri: {}", uri, e);
      Utils.rethrowException(e);
      throw new AssertionError("Should not reach this");
    } finally {
      if (method != null) {
        method.releaseConnection();
      }
    }
  }

  public static int sendSegmentJson(final String host, final String port, final JSONObject segmentJson) {
    return sendSegmentJson(host, port, segmentJson, MAX_RETRIES, SLEEP_BETWEEN_RETRIES_IN_SECONDS);
  }

  public static int sendSegmentJson(final String host, final String port, final JSONObject segmentJson,
      final int maxRetries, final int sleepTimeSec) {
    for (int numRetries = 0; ; numRetries++) {
      try {
        return sendSegmentJsonImpl(host, port, segmentJson);
      } catch (Exception e) {
        if (numRetries >= maxRetries) {
          Utils.rethrowException(e);
        }
        try {
          Thread.sleep(sleepTimeSec * 1000);
        } catch (Exception e1) {
          LOGGER.error("Upload of JSON " + " to host " + host + " interrupted while waiting to retry after error");
          Utils.rethrowException(e1);
        }
      }
    }
  }


  public static int sendSegmentJsonImpl(final String host, final String port, final JSONObject segmentJson) {
    PostMethod postMethod = null;
    try {
      RequestEntity requestEntity = new StringRequestEntity(
          segmentJson.toString(),
          ContentType.APPLICATION_JSON.getMimeType(),
          ContentType.APPLICATION_JSON.getCharset().name());
      postMethod = new PostMethod("http://" + host + ":" + port + "/" + SEGMENTS_PATH);
      postMethod.setRequestEntity(requestEntity);
      postMethod.setRequestHeader(UPLOAD_TYPE, FileUploadType.JSON.toString());
      postMethod.setRequestHeader(HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
      int statusCode = FILE_UPLOAD_HTTP_CLIENT.executeMethod(postMethod);
      if (statusCode >= 400) {
        String errorString = "POST Status Code: " + statusCode + "\n";
        if (postMethod.getResponseHeader("Error") != null) {
          errorString += "ServletException: " + postMethod.getResponseHeader("Error").getValue();
        }
        throw new HttpException(errorString);
      }
      return statusCode;
    } catch (Exception e) {
      LOGGER.error("Caught exception while sending json: {}", segmentJson.toString(), e);
      Utils.rethrowException(e);
      throw new AssertionError("Should not reach this");
    } finally {
      if (postMethod != null) {
        postMethod.releaseConnection();
      }
    }
  }

  public static long getFile(String url, File file) throws Exception {
    GetMethod httpget = null;
    try {
      httpget = new GetMethod(url);
      int responseCode = FILE_UPLOAD_HTTP_CLIENT.executeMethod(httpget);
      long contentLength = httpget.getResponseContentLength();
      InputStream is = httpget.getResponseBodyAsStream();
      return storeFile(url, file, responseCode, contentLength, is);
    } catch (Exception ex) {
      LOGGER.error("Caught exception", ex);
      throw ex;
    } finally {
      if (httpget != null) {
        httpget.releaseConnection();
      }
    }
  }

  public static long storeFile(String url, File file, int responseCode, long contentLength, InputStream is)
      throws IOException {
    if (responseCode >= 400) {
      if (contentLength > 0) {
        // don't read more than 1000 bytes
        byte[] buffer = new byte[(int) Math.min(contentLength, 1000)];
        is.read(buffer);
        LOGGER.error("Error response from url:{} \n {}", url, new String(buffer));
      }
      String errMsg = "Received error response from server while downloading file. url:" + url
          + " response code:" + responseCode;
      if (responseCode >= 500) {
        // Caller may retry.
        throw new RuntimeException(errMsg);
      } else {
        throw new PermanentDownloadException(errMsg);
      }
    } else {
      BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(file));
      IOUtils.copyLarge(is, output);
      IOUtils.closeQuietly(output);
      if (contentLength != -1 && contentLength != file.length()) {
        // The content-length header was present and does not match the file length.
        throw new RuntimeException("File length " + file.length() + " does not match content length " + contentLength);
      }
      return contentLength;
    }
  }

  public enum FileUploadType {
    URI,
    JSON,
    TAR; // Default value

    public FileUploadType valueOf(Object o) {
      if (o != null) {
        String ostring = o.toString();
        for (FileUploadType u : FileUploadType.values()) {
          if (ostring.equalsIgnoreCase(u.toString())) {
            return u;
          }
        }
      }
      return getDefaultUploadType();
    }

    public static FileUploadType getDefaultUploadType() {
      return TAR;
    }
  }

    public static int sendSegment(final String uriString, final String segmentName, final int pushTimeoutMs,
        InputStream is, int maxRetries, int sleepTimeSec) {
      return sendFileRetry(uriString, segmentName, pushTimeoutMs, is, maxRetries, sleepTimeSec);
    }

    public static int sendFileRetry(final String uri, final String segmentName, int pushTimeoutMs, InputStream inputStream,
        int maxRetries, int sleepTimeSec) {
      int retval;
      for (int numRetries = 0; ; numRetries++) {
        try {
          retval = sendFile(uri, segmentName, inputStream, pushTimeoutMs);
          break;
        } catch (Exception e) {
          if (e instanceof PermanentPushFailureException) {
            throw new PermanentPushFailureException("Exiting job", e);
          }
          if (numRetries >= maxRetries) {
            throw new RuntimeException(e);
          }
          LOGGER.warn("Retry " + numRetries + " of Upload of File " + segmentName + " to URI " + uri
              + " after error trying to send file  Sleeping for " + sleepTimeSec + " seconds. Caught Exception", e);
          try {
            Thread.sleep(sleepTimeSec * 1000);
          } catch (Exception e1) {
            LOGGER.warn(
                "Upload of File " + segmentName + " to URI " + uri + " interrupted while waiting to retry after error. Caught Exception ", e1);
            throw new RuntimeException(e1);
          }
        }
      }
      return retval;
    }

    public static int sendFile(final String uri, final String segmentName, final InputStream inputStream,
        int pushTimeoutMs) {
      // this method returns the response code only on success, and throws exception otherwise
      LOGGER.info("Sending file " + segmentName + " to " + uri);
      final BasicHttpParams httpParams = new BasicHttpParams();
      // TODO: Fix when new version is updated
      httpParams.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, pushTimeoutMs);
      org.apache.http.client.HttpClient client = new DefaultHttpClient(httpParams);

      try {
        LOGGER.info("URI is: " + uri);
        HttpPost post = new HttpPost(uri);
        String boundary = "-------------" + System.currentTimeMillis();

        post.setHeader("Content-type", "multipart/form-data; boundary=" + boundary);
        InputStreamBody contentBody =
            new InputStreamBody(inputStream, ContentType.APPLICATION_OCTET_STREAM.toString(), segmentName);

        MultipartEntity entity =
            new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE, boundary, Charset.forName("UTF-8"));
        FormBodyPart bodyPart = new FormBodyPart(segmentName, contentBody);
        entity.addPart(bodyPart);
        post.setEntity(entity);

        // execute the request
        HttpResponse response = client.execute(post);

        // retrieve and process the response
        int responseCode = response.getStatusLine().getStatusCode();
        String host = NOT_IN_RESPONSE;
        if (response.containsHeader(HDR_CONTROLLER_HOST)) {
          host = response.getFirstHeader(HDR_CONTROLLER_HOST).getValue();
        }
        String version = NOT_IN_RESPONSE;
        if (response.containsHeader(HDR_CONTROLLER_VERSION)) {
          version = response.getFirstHeader(HDR_CONTROLLER_VERSION).getValue();
        }
        LOGGER.info("Controller host:" + host + ",Controller version:" + version + "(file:" + segmentName + ")");

        // Throws a permanent exception to immediately kill job
        if (responseCode >= 400 && responseCode < 500) {
          String errorString = "Response Code: " + responseCode;
          LOGGER.error("Error " + errorString + " trying to send file " + segmentName + " to " + uri);
          InputStream content = response.getEntity().getContent();
          String respBody = org.apache.commons.io.IOUtils.toString(content);
          if (respBody != null && !respBody.isEmpty()) {
            LOGGER.error("Response body for file " + segmentName + " uri " + uri + ":" + respBody);
          }
          throw new PermanentPushFailureException(errorString);
        }

        // Runtime exception allows retry for server errors
        if (responseCode >= 500) {
          String errorString = "Response Code: " + responseCode;
          LOGGER.warn("Transient error " + errorString + " sending " + segmentName + " to " + uri);
          InputStream content = response.getEntity().getContent();
          String respBody = org.apache.commons.io.IOUtils.toString(content);
          if (respBody != null && !respBody.isEmpty()) {
            LOGGER.info("Response body for file " + segmentName + " uri " + uri + ":" + respBody);
          }
          throw new RuntimeException(errorString);
        }

        return responseCode;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

  }
}
