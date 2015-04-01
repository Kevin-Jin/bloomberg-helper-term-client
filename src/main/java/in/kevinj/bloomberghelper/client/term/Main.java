package in.kevinj.bloomberghelper.client.term;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import org.apache.http.Consts;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.Args;
import org.apache.http.util.EntityUtils;

public class Main {
	private static InputStream createInputStream() throws IOException {
		PipedOutputStream output = new PipedOutputStream();
		new Thread(() -> {
			try (OutputStreamWriter writer = new OutputStreamWriter(output)) {
				//TODO: heartbeat every 30 seconds
				for (int i = 0; i < 10; i++) {
					Thread.sleep(2000);
					writer.write(i + "\r\n");
					writer.flush();
					System.out.print(i + "\r\n");
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}).start();
		return new PipedInputStream(output);
	}

	public static void main(String[] args) throws ClientProtocolException, IOException {
		try (CloseableHttpClient httpclient = HttpClients.createDefault(); InputStream input = createInputStream()) {
			HttpPost httppost = new HttpPost("http://localhost:25274/bloomberghelper/TERM_CLIENT/register");
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
			try (CloseableHttpResponse response = httpclient.execute(httppost)) {
				System.out.println("----------------------------------------");
				System.out.println(response.getStatusLine());
				EntityUtils.consume(response.getEntity());
			}
		}
	}
}
