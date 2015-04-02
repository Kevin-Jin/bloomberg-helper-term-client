package in.kevinj.bloomberghelper.common;

import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class HashFunctions {
	public static final Charset ASCII = Charset.forName("US-ASCII");

	private static ThreadLocal<MessageDigest> sha512digest = new ThreadLocal<MessageDigest>() {
		@Override
		public MessageDigest initialValue() {
			try {
				return MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				return null;
			}
		}
	};

	private static byte[] hashWithDigest(byte[] in, MessageDigest digester) {
		digester.update(in, 0, in.length);
		return digester.digest();
	}

	private static byte[] sha256(byte[] in) {
		return hashWithDigest(in, sha512digest.get());
	}

	public static byte[] sha256(String in) {
		return sha256(in.getBytes(ASCII));
	}

	public static boolean checkSha512Hash(byte[] actualHash, String check) {
		return Arrays.equals(actualHash, sha256(check.getBytes(ASCII)));
	}

	public static byte[] hmacSha512(String key, String password, String body) {
		SecretKeySpec keySpec = new SecretKeySpec(sha256(key + password), "HmacSHA256");

		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(keySpec);
			return mac.doFinal("foo".getBytes());
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
			return null;
		} catch (InvalidKeyException e) {
			e.printStackTrace();
			return null;
		}
	}
}
