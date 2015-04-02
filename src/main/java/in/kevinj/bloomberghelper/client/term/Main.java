package in.kevinj.bloomberghelper.client.term;

import in.kevinj.bloomberghelper.common.HashFunctions;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Scanner;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.util.Args;
import org.apache.http.util.EntityUtils;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.dom.Header;
import org.apache.james.mime4j.message.SimpleContentHandler;
import org.apache.james.mime4j.parser.MimeStreamParser;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.MimeConfig;

public class Main {
	private static String readAll(HttpEntity ent) {
		if (!ContentType.get(ent).getMimeType().equals(ContentType.TEXT_PLAIN.getMimeType()) || ent.isChunked())
			return null;

		Charset charset;
		if (ContentType.get(ent) != null && ContentType.get(ent).getCharset() != null)
			charset = ContentType.get(ent).getCharset();
		else
			charset = Charset.defaultCharset();
		long length = ent.getContentLength();

		StringBuilder sb = new StringBuilder(Math.max((int) length, 0));
		char[] buffer = new char[4096];
		try (Reader resp = new InputStreamReader(ent.getContent(), charset)) {
			int read;
			while ((read = resp.read(buffer)) != -1)
				sb.append(buffer, 0, read);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

		return sb.toString();
	}

	private static BasicClientCookie createAuthCookie(String key, String password, String endpoint) {
		long expiration = System.currentTimeMillis() + 30000;
		BasicClientCookie cookie = new BasicClientCookie("requestkey", expiration + ";" + Base64.encodeBase64String(HashFunctions.hmacSha512(key, password, expiration + ";" + endpoint)));
		cookie.setDomain("localhost");
		cookie.setPath("/bloomberghelper");
		return cookie;
	}

	private static boolean initializeInboundConnection(HttpHost targetHost, boolean register, HttpClientContext context) throws IOException {
		System.out.println("Subscribing to bloomberg-helper-daemon instance at " + targetHost);
		HttpGet httpget = new HttpGet("/bloomberghelper/TERM_CLIENT/subscribe?new=" + register);
		CloseableHttpClient httpclient = HttpClients.createDefault();
		CloseableHttpResponse response = httpclient.execute(targetHost, httpget, context);
		boolean canClose = true;
		try {
			if (response.getStatusLine().getStatusCode() == 401) {
				System.out.println("Authentication error: " + readAll(response.getEntity()));
				return false;
			} else {
				System.out.println("Listening for file changes made on bloomberg-dev-client");
				/*assert ContentType.get(response.getEntity()).getMimeType().equals(ContentType.APPLICATION_OCTET_STREAM.getMimeType());
				byte[] buffer = new byte[4096];
				try (InputStream resp = response.getEntity().getContent()) {
					int read;
					while ((read = resp.read(buffer)) != -1)
						System.out.println("READ " + read + "");
				} catch (IOException e) {
					e.printStackTrace();
				}*/
				/*assert ContentType.get(response.getEntity()).getMimeType().equals(ContentType.MULTIPART_FORM_DATA.getMimeType());
				char[] buffer = new char[4096];
				try (Reader resp = new InputStreamReader(response.getEntity().getContent())) {
					int read;
					while ((read = resp.read(buffer)) != -1)
						System.out.print(new String(buffer, 0, read));
				} catch (IOException e) {
					e.printStackTrace();
				}*/
				new Thread(() -> {
					try {
						MimeConfig config = new MimeConfig();
						config.setHeadlessParsing(ContentType.get(response.getEntity()).toString());
						MimeStreamParser parser = new MimeStreamParser(config);
						parser.setContentHandler(new SimpleContentHandler() {
							@Override
							public void headers(Header header) {
								
							}

							@Override
							public void body(BodyDescriptor bd, InputStream is) throws MimeException, IOException {
								System.out.println("DAEMON RECV " + bd.getMimeType() + " " + bd.getContentLength() + " " + String.valueOf(IOUtils.toCharArray(is, bd.getCharset())));
							}
						});
						parser.parse(response.getEntity().getContent());
					} catch (IOException | MimeException e) {
						e.printStackTrace();
					} finally {
						try {
							response.close();
							httpclient.close();
						} catch (Exception e) { }
					}
				}).start();
				canClose = false;
				return true;
			}
		} finally {
			if (canClose) {
				response.close();
				httpclient.close();
			}
		}
	}

	private static InputStream createInputStream() throws IOException {
		PipedOutputStream output = new PipedOutputStream();
		new Thread(() -> {
			try (OutputStreamWriter writer = new OutputStreamWriter(output)) {
				//TODO: heartbeat every 30 seconds
				for (int i = 0; i < 10; i++) {
					Thread.sleep(2000);
					writer.write(i + "\r\n");
					writer.flush();
					System.out.print("DAEMON SEND " + i + "\r\n");
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}).start();
		return new PipedInputStream(output);
	}

	private static void initializeOutboundConnection(HttpHost targetHost) throws IOException {
		try (CloseableHttpClient httpclient = HttpClients.createDefault(); InputStream input = createInputStream()) {
			HttpPost httppost = new HttpPost("/bloomberghelper/TERM_CLIENT/publish");
			InputStreamEntity reqEntity = new InputStreamEntity(input, -1, ContentType.create("text/plain", Consts.UTF_8)) {
				@Override
				public void writeTo(final OutputStream outstream) throws IOException {
					Args.notNull(outstream, "Output stream");
					final InputStream instream = this.getContent();
					try {
						final byte[] buffer = new byte[OUTPUT_BUFFER_SIZE];
						int l;
						if (this.getContentLength() < 0) {
							// consume until EOF
							while ((l = instream.read(buffer)) != -1) {
								outstream.write(buffer, 0, l);
								outstream.flush(); //ChunkedOutputStream is buffered
							}
						} else {
							// consume no more than length
							long remaining = this.getContentLength();
							while (remaining > 0) {
								l = instream.read(buffer, 0, (int) Math.min(OUTPUT_BUFFER_SIZE, remaining));
								if (l == -1) {
									break;
								}
								outstream.write(buffer, 0, l);
								remaining -= l;
							}
						}
					} finally {
						instream.close();
					}
				}
			};
			reqEntity.setChunked(true);
			httppost.setEntity(reqEntity);

			System.out.println("Executing request: " + httppost.getRequestLine());
			try (CloseableHttpResponse response = httpclient.execute(targetHost, httppost)) {
				System.out.println("----------------------------------------");
				System.out.println(response.getStatusLine());
				EntityUtils.consume(response.getEntity());
			}
		}
	}

	public static void main(String[] args) throws ClientProtocolException, IOException {
		try (Scanner scan = new Scanner(System.in)) {
			HttpClientContext context = HttpClientContext.create();
			HttpHost targetHost = new HttpHost("localhost", 25274, "http");
			CredentialsProvider credsProvider = new BasicCredentialsProvider();

			context.setCredentialsProvider(credsProvider);
			boolean authFailed;
			String key, password, line;
			boolean register = true;
			do {
				askHost: while (true) {
					System.out.print("Enter the bloomberg-helper-daemon host [" + targetHost + "]: ");
					if (!(line = scan.nextLine().trim()).isEmpty()) {
						try {
							targetHost = HttpHost.create(line);
							break askHost;
						} catch (IllegalArgumentException e) {
							System.out.println("Please try again.");
						}
					} else {
						//use default
						break askHost;
					}
				}
				askRegister: while (true) {
					System.out.print("Is your bloomberg-helper-dev-client already connected to the bloomberg-helper-daemon instance (Y/N)? [" + (register ? "N" : "Y") + "]: ");
					switch (scan.nextLine().trim()) {
						case "Y":
						case "y":
							register = false;
							break askRegister;
						case "N":
						case "n":
						case "":
							register = true;
							break askRegister;
						default:
							System.out.println("Please try again.");
							break;
					}
				}
				askKey: while (true) {
					System.out.print("Enter a memorable key: ");
					key = scan.nextLine().trim();
					if (!key.contains(":") && !key.isEmpty())
						break askKey;
					else
						System.out.println("Please try again.");
				}
				askPwd: while (true) {
					System.out.print("Enter a password: ");
					password = scan.nextLine().trim();
					if (!password.contains(":") && !key.isEmpty())
						break askPwd;
					else
						System.out.println("Please try again.");
				}
				credsProvider.setCredentials(new AuthScope(targetHost.getHostName(), targetHost.getPort()), new UsernamePasswordCredentials(key, password));
				AuthCache authCache = new BasicAuthCache();
				authCache.put(targetHost, new BasicScheme());
				context.setAuthCache(authCache);
				authFailed = !initializeInboundConnection(targetHost, register, context);
				context.removeAttribute(HttpClientContext.AUTH_CACHE);
			} while (authFailed);
			CookieStore cookieStore = new BasicCookieStore(); 
			cookieStore.addCookie(createAuthCookie(key, password, "/bloomberghelper/TERM_CLIENT/publish"));
			context.setCookieStore(cookieStore);
			context.removeAttribute(HttpClientContext.CREDS_PROVIDER);

			initializeOutboundConnection(targetHost);
		}
	}
}
