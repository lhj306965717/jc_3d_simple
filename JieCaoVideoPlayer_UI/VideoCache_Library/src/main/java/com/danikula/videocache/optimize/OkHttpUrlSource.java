package com.danikula.videocache.optimize;

import android.text.TextUtils;
import android.util.Log;

import com.danikula.videocache.InterruptedProxyCacheException;
import com.danikula.videocache.ProxyCacheException;
import com.danikula.videocache.ProxyCacheUtils;
import com.danikula.videocache.Source;
import com.danikula.videocache.SourceInfo;
import com.danikula.videocache.sourcestorage.SourceInfoStorage;
import com.danikula.videocache.sourcestorage.SourceInfoStorageFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.danikula.videocache.Preconditions.checkNotNull;
import static com.danikula.videocache.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;

/**
 * Created by LiaoHongjie on 2017/7/13.
 */

public class OkHttpUrlSource implements Source {
    private static final int MAX_REDIRECTS = 5;
    private final SourceInfoStorage sourceInfoStorage;
    private SourceInfo sourceInfo;
    private OkHttpClient mOkHttpClient;
    private Call requestCall = null;
    private InputStream inputStream;

    public OkHttpUrlSource(String url) {
        this(url, SourceInfoStorageFactory.newEmptySourceInfoStorage());
    }

    public OkHttpUrlSource(String url, SourceInfoStorage sourceInfoStorage) {
        this.sourceInfoStorage = checkNotNull(sourceInfoStorage);
        SourceInfo sourceInfo = sourceInfoStorage.get(url);
        this.sourceInfo = sourceInfo != null ? sourceInfo :
                new SourceInfo(url, Integer.MIN_VALUE, ProxyCacheUtils.getSupposablyMime(url));

        mOkHttpClient = MyOkHttpClient.getInstance(); //初始化
    }

    public OkHttpUrlSource(OkHttpUrlSource source) {
        this.sourceInfo = source.sourceInfo;
        this.sourceInfoStorage = source.sourceInfoStorage;
    }

    @Override
    public synchronized long length() throws ProxyCacheException {
        if (sourceInfo.length == Integer.MIN_VALUE) {
            fetchContentInfo();
        }
        return sourceInfo.length;
    }

    @Override
    public void open(long offset) throws ProxyCacheException {
        try {
            Response response = openConnection(offset);
            String mime = response.header("Content-Type");
            this.inputStream = new BufferedInputStream(response.body().byteStream(), DEFAULT_BUFFER_SIZE);
            long length = readSourceAvailableBytes(response, offset, response.code());
            this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
            this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
        } catch (IOException e) {
            throw new ProxyCacheException("Error opening okHttpClient for " + sourceInfo.url + " with offset " + offset, e);
        }
    }

    private long readSourceAvailableBytes(Response response, long offset, int responseCode) throws IOException {
        long contentLength = getContentLength(response);
        return responseCode == HTTP_OK ? contentLength
                : responseCode == HTTP_PARTIAL ? contentLength + offset : sourceInfo.length;
    }

    private long getContentLength(Response response) {
        String contentLengthValue = response.header("Content-Length");
        return contentLengthValue == null ? -1 : Long.parseLong(contentLengthValue);
    }

    @Override
    public void close() throws ProxyCacheException {
        if (mOkHttpClient != null && inputStream != null && requestCall != null) {
            try {
                inputStream.close();
                requestCall.cancel();
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    @Override
    public int read(byte[] buffer) throws ProxyCacheException {
        if (inputStream == null) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url + ": okHttpClient is absent!");
        }
        try {
            return inputStream.read(buffer, 0, buffer.length);
        } catch (InterruptedIOException e) {
            throw new InterruptedProxyCacheException("Reading source " + sourceInfo.url + " is interrupted", e);
        } catch (IOException e) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url, e);
        }
    }

    /** 获取头文件
     * @throws ProxyCacheException
     */
    private void fetchContentInfo() throws ProxyCacheException {
       // Log.e("TAG", "Read content info from " + sourceInfo.url);
        Response response = null;
        InputStream inputStream = null;
        try {
            response = openConnectionForHeader(20000);
            if (response == null || !response.isSuccessful()) {
                throw new ProxyCacheException("Fail to fetchContentInfo: " + sourceInfo.url);
            }
            long length = getContentLength(response);
            String mime = response.header("Content-Type", "application/mp4");
            inputStream = response.body().byteStream();
            this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
            this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
           // Log.e("TAG", "Content info for " + sourceInfo.url + " : mime: " + mime + ", content-length: " + length);
        } catch (IOException e) {
            Log.e("TAG", "Error fetching info from " + sourceInfo.url, e);
        } finally {
            ProxyCacheUtils.close(inputStream);
            if (response != null && requestCall != null) {
                requestCall.cancel();
            }
        }
    }

    // for HEAD
    private Response openConnectionForHeader(int timeout) throws IOException, ProxyCacheException {
        Response response;
        boolean isRedirect = false;
        String newUrl = this.sourceInfo.url;
        int redirectCount = 0;
        do {
            //只返回头部，不需要BODY，既可以提高响应速度也可以减少网络流量
            Request request = new Request.Builder()
                    .head()
                    .url(newUrl)
                    .build();
            requestCall = mOkHttpClient.newCall(request);
            response = requestCall.execute();
            if (response.isRedirect()) {
                newUrl = response.header("Location");
                isRedirect = response.isRedirect();
                redirectCount++;
                requestCall.cancel();
            }
            if (redirectCount > MAX_REDIRECTS) {
                throw new ProxyCacheException("Too many redirects: " + redirectCount);
            }
        } while (isRedirect);

        return response;
    }

    private Response openConnection(long offset) throws IOException, ProxyCacheException {

        Response response;
        boolean isRedirect = false;
        String newUrl = this.sourceInfo.url;
        int redirectCount = 0;
        do {
            //Log.e("TAG", "Open connection" + (offset > 0 ? " with offset " + offset : "") + " to " + sourceInfo.url);
            Request.Builder requestBuilder = new Request.Builder()
                    .get()
                    .url(newUrl);
            if (offset > 0) {
                requestBuilder.addHeader("Range", "bytes=" + offset + "-");
            }
            requestCall = mOkHttpClient.newCall(requestBuilder.build());
            response = requestCall.execute();
            if (response.isRedirect()) {
                newUrl = response.header("Location");
                isRedirect = response.isRedirect();
                redirectCount++;
            }
            if (redirectCount > MAX_REDIRECTS) {
                throw new ProxyCacheException("Too many redirects: " + redirectCount);
            }
        } while (isRedirect);

        return response;
    }

    public synchronized String getMime() throws ProxyCacheException {
        if (TextUtils.isEmpty(sourceInfo.mime)) {
            fetchContentInfo();
        }
        return sourceInfo.mime;
    }

    public String getUrl() {
        return sourceInfo.url;
    }

    @Override
    public String toString() {
        return "OkHttpUrlSource{sourceInfo='" + sourceInfo + "}";
    }
}