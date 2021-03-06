// Copyright 2017 Google Inc.
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
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.subtle;

import com.google.crypto.tink.StreamingAead;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Streaming encryption using AES-CTR and HMAC.
 *
 * Each ciphertext uses a new AES-CTR key and HMAC key that ared derived from the key derivation
 * key, a randomly chosen salt of the same size as the key and a nonce prefix using HKDF.
 *
 * The the format of a ciphertext is
 *   header || segment_0 || segment_1 || ... || segment_k.
 * The header has size this.headerLength(). Its format is
 *   headerLength || salt || prefix.
 * where headerLength is 1 byte determining the size of the header, salt is a salt used in the
 * key derivation and prefix is the prefix of the nonce.
 * In principle headerLength is redundant information, since the length of the header can be
 * determined from the key size.
 *
 * segment_i is the i-th segment of the ciphertext. The size of segment_1 .. segment_{k-1}
 * is ciphertextSegmentSize. segment_0 is shorter, so that segment_0, the header
 * and other information of size firstSegmentOffset align with ciphertextSegmentSize.
 */
public final class AesCtrHmacStreaming implements StreamingAead {
  // TODO(bleichen): Some things that are not yet decided:
  //   - What can we assume about the state of objects after getting an exception?
  //   - Should there be a simple test to detect invalid ciphertext offsets?
  //   - Should we encode the size of the header?
  //   - Should we encode the size of the segments?
  //   - Should a version be included in the header to allow header modification?
  //   - Should we allow other information, header and the first segment the to be a multiple of
  //     ciphertextSegmentSize?
  //   - This implementation fixes a number of parameters. Should there be more options?
  //   - Should we authenticate ciphertextSegmentSize and firstSegmentSize?
  //     If an attacker can change these parameters then this would allow to move
  //     the position of plaintext in the file.
  //
  // The size of the nonce for AES-CTR
  private static final int NONCE_SIZE_IN_BYTES = 16;

  // The nonce has the format nonce_prefix || ctr || last_block || 0 0 0 0
  // The nonce_prefix is constant for the whole file.
  // The ctr is a 32 bit ctr, the last_block is 1 if this is the
  // last block of the file and 0 otherwise.
  private static final int NONCE_PREFIX_IN_BYTES = 7;

  // The MAC algorithm used for the key derivation
  private static final String HKDF_ALGORITHM = "HMACSHA256";

  // The MAC algorithm used for authentication
  // TODO(bleichen): Probably should be a parameter.
  private static final String TAG_ALGORITHM = "HMACSHA256";

  private static final int HMAC_KEY_SIZE_IN_BYTES = 32;

  private int keySizeInBytes;
  private int tagSizeInBytes;
  private int ciphertextSegmentSize;
  private int plaintextSegmentSize;
  private int firstSegmentOffset;
  private byte[] ikm;

  /**
   * Initializes a streaming primitive with a key derivation key and encryption parameters.
   * @param ikm input keying material used to derive sub keys.
   * @param keySizeInBytes the key size of the sub keys
   * @param tagSizeInBytes the size authentication tags
   * @param ciphertextSegmentSize the size of ciphertext segments.
   * @param firstSegmentOffset the offset of the first ciphertext segment. That means the first
   *    segment has size ciphertextSegmentSize - headerLength() - firstSegmentOffset
   * @throws InvalidAlgorithmParameterException if ikm is too short, the key size not supported or
   *    ciphertextSegmentSize is to short.
   */
  public AesCtrHmacStreaming(
      byte[] ikm,
      int keySizeInBytes,
      int tagSizeInBytes,
      int ciphertextSegmentSize,
      int firstSegmentOffset) throws InvalidAlgorithmParameterException {
    validateParameters(ikm.length, keySizeInBytes, tagSizeInBytes, ciphertextSegmentSize,
                       firstSegmentOffset);
    this.ikm = Arrays.copyOf(ikm, ikm.length);
    this.keySizeInBytes = keySizeInBytes;
    this.tagSizeInBytes = tagSizeInBytes;
    this.ciphertextSegmentSize = ciphertextSegmentSize;
    this.firstSegmentOffset = firstSegmentOffset;
    this.plaintextSegmentSize = ciphertextSegmentSize - tagSizeInBytes;
  }

  private static void validateParameters(
      int ikmSize,
      int keySizeInBytes,
      int tagSizeInBytes,
      int ciphertextSegmentSize,
      int firstSegmentOffset) throws InvalidAlgorithmParameterException {
    if (ikmSize < keySizeInBytes) {
      throw new InvalidAlgorithmParameterException("ikm to short");
    }
    boolean isValidKeySize = keySizeInBytes == 16 || keySizeInBytes == 24 || keySizeInBytes == 32;
    if (!isValidKeySize) {
      throw new InvalidAlgorithmParameterException("Invalid key size");
    }
    if (tagSizeInBytes < 12 || tagSizeInBytes > 32) {
      throw new InvalidAlgorithmParameterException("Invalid tag size " + tagSizeInBytes);
    }
    int firstPlaintextSegment = ciphertextSegmentSize - firstSegmentOffset - tagSizeInBytes
        - keySizeInBytes - NONCE_PREFIX_IN_BYTES - 1;
    if (firstPlaintextSegment <= 0) {
      throw new InvalidAlgorithmParameterException("ciphertextSegmentSize too small");
    }
  }

  public int getFirstSegmentOffset() {
    return firstSegmentOffset;
  }

  public int headerLength() {
    return 1 + keySizeInBytes + NONCE_PREFIX_IN_BYTES;
  }

  private int ciphertextOffset() {
    return headerLength() + firstSegmentOffset;
  }

  /**
   * Returns the number of bytes that a ciphertext segment
   * is longer than the corresponding plaintext segment.
   * Typically this is the size of the tag.
   */
  private int ciphertextOverhead() {
    return tagSizeInBytes;
  }

  /**
   * Returns the expected size of the ciphertext for a given plaintext
   * The returned value includes the header and offset.
   */
  public long expectedCiphertextSize(long plaintextSize) {
    long offset = ciphertextOffset();
    long fullSegments = (plaintextSize + offset) / plaintextSegmentSize;
    long ciphertextSize = fullSegments * ciphertextSegmentSize;
    long lastSegmentSize = (plaintextSize + offset) % plaintextSegmentSize;
    if (lastSegmentSize > 0) {
      ciphertextSize += lastSegmentSize + tagSizeInBytes;
    }
    return ciphertextSize;
  }

  private static Cipher cipherInstance() throws GeneralSecurityException {
    return EngineFactory.CIPHER.getInstance("AES/CTR/NoPadding");
  }

  private static Mac macInstance() throws GeneralSecurityException {
    return EngineFactory.MAC.getInstance(TAG_ALGORITHM);
  }

  private byte[] randomSalt() {
    return Random.randBytes(keySizeInBytes);
  }

  public int getCiphertextSegmentSize() {
    return ciphertextSegmentSize;
  }


  private byte[] nonceForSegment(byte[] prefix, int segmentNr, boolean last) {
    ByteBuffer nonce = ByteBuffer.allocate(NONCE_SIZE_IN_BYTES);
    nonce.order(ByteOrder.BIG_ENDIAN);
    nonce.put(prefix);
    nonce.putInt(segmentNr);
    nonce.put((byte) (last ? 1 : 0));
    nonce.putInt(0);
    return nonce.array();
  }

  private byte[] randomNonce() {
    return Random.randBytes(NONCE_PREFIX_IN_BYTES);
  }

  private byte[] deriveKeyMaterial(
      byte[] salt,
      byte[] aad) throws GeneralSecurityException {
    int keyMaterialSize = keySizeInBytes + HMAC_KEY_SIZE_IN_BYTES;
    return  Hkdf.computeHkdf(HKDF_ALGORITHM, ikm, salt, aad, keyMaterialSize);
  }

  private SecretKeySpec deriveKeySpec(byte[] keyMaterial) throws GeneralSecurityException {
    return new SecretKeySpec(keyMaterial, 0, keySizeInBytes, "AES");
  }

  private SecretKeySpec deriveHmacKeySpec(byte[] keyMaterial) throws GeneralSecurityException {
    return new SecretKeySpec(keyMaterial, keySizeInBytes, HMAC_KEY_SIZE_IN_BYTES, 
                             TAG_ALGORITHM);
  }

  /**
   * Returns a WritableByteChannel for plaintext.
   * @param ciphertextChannel the channel to which the ciphertext is written.
   * @param associatedData data associated with the plaintext. This data is authenticated
   *        but not encrypted. It must be passed into the decryption.
   */
  @Override
  public WritableByteChannel newEncryptingChannel(
      WritableByteChannel ciphertextChannel, byte[] associatedData)
      throws GeneralSecurityException, IOException {
    AesCtrHmacStreamEncrypter encrypter = new AesCtrHmacStreamEncrypter(associatedData);
    return new StreamingAeadEncryptingChannel(
        encrypter,
        ciphertextChannel,
        plaintextSegmentSize,
        ciphertextSegmentSize,
        ciphertextOffset());
  }

  @Override
  public ReadableByteChannel newDecryptingChannel(
      ReadableByteChannel ciphertextChannel,
      byte[] associatedData)
      throws GeneralSecurityException, IOException {
    return new StreamingAeadDecryptingChannel(
        new AesCtrHmacStreamDecrypter(),
        ciphertextChannel,
        associatedData,
        plaintextSegmentSize,
        ciphertextSegmentSize,
        ciphertextOffset(),
        headerLength());
  }

  @Override
  public SeekableByteChannel newSeekableDecryptingChannel(
      SeekableByteChannel ciphertextSource,
      byte[] associatedData)
      throws GeneralSecurityException, IOException {
    return new StreamingAeadSeekableDecryptingChannel(
        new AesCtrHmacStreamDecrypter(),
        ciphertextSource,
        associatedData,
        plaintextSegmentSize,
        ciphertextSegmentSize,
        ciphertextOffset(),
        ciphertextOverhead(),
        headerLength());
  }

  /**
   * An instance of a crypter used to encrypt a plaintext stream.
   * The instances have state: encryptedSegments counts the number of encrypted
   * segments. This state is used to generate the IV for each segment.
   * By enforcing that only the method encryptSegment can increment this state,
   * we can guarantee that the IV does not repeat.
   */
  class AesCtrHmacStreamEncrypter implements StreamSegmentEncrypter {
    private final SecretKeySpec keySpec;
    private final SecretKeySpec hmacKeySpec;
    private final Cipher cipher;
    private final Mac mac;
    private final byte[] noncePrefix;
    private ByteBuffer header;
    private int encryptedSegments = 0;

    public AesCtrHmacStreamEncrypter(byte[] aad) throws GeneralSecurityException {
      cipher = cipherInstance();
      mac = macInstance();
      encryptedSegments = 0;
      byte[] salt = randomSalt();
      noncePrefix = randomNonce();
      header = ByteBuffer.allocate(headerLength());
      header.put((byte) headerLength());
      header.put(salt);
      header.put(noncePrefix);
      header.flip();
      byte[] keymaterial = deriveKeyMaterial(salt, aad);
      keySpec = deriveKeySpec(keymaterial);
      hmacKeySpec = deriveHmacKeySpec(keymaterial);
    }

    @Override
    public ByteBuffer getHeader() {
      return header.asReadOnlyBuffer();
    }

    /**
     * Encrypts the next plaintext segment.
     * This uses encryptedSegments as the segment number for the encryption.
     */
    @Override
    public synchronized void encryptSegment(
        ByteBuffer plaintext, boolean isLastSegment, ByteBuffer ciphertext)
        throws GeneralSecurityException {
      int position = ciphertext.position();
      byte[] nonce = nonceForSegment(noncePrefix, encryptedSegments, isLastSegment);
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(nonce));
      encryptedSegments++;
      cipher.doFinal(plaintext, ciphertext);
      ByteBuffer ctCopy = ciphertext.duplicate();
      ctCopy.flip();
      ctCopy.position(position);
      mac.init(hmacKeySpec);
      mac.update(nonce);
      mac.update(ctCopy);
      byte[] tag = mac.doFinal();
      ciphertext.put(tag, 0, tagSizeInBytes);
    }

    /**
     * Encrypt a segment consisting of two parts.
     * This method simplifies the case where one part of the plaintext is buffered
     * and the other part is passed in by the caller.
     */
    @Override
    public synchronized void encryptSegment(
        ByteBuffer part1, ByteBuffer part2, boolean isLastSegment, ByteBuffer ciphertext)
        throws GeneralSecurityException {
      int position = ciphertext.position();
      byte[] nonce = nonceForSegment(noncePrefix, encryptedSegments, isLastSegment);
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(nonce));
      encryptedSegments++;
      cipher.update(part1, ciphertext);
      cipher.doFinal(part2, ciphertext);
      ByteBuffer ctCopy = ciphertext.duplicate();
      ctCopy.flip();
      ctCopy.position(position);
      mac.init(hmacKeySpec);
      mac.update(nonce);
      mac.update(ctCopy);
      byte[] tag = mac.doFinal();
      ciphertext.put(tag, 0, tagSizeInBytes);
    }

    @Override
    public int getEncryptedSegments() {
      return encryptedSegments;
    }
  }

  /**
   * An instance of a crypter used to decrypt a ciphertext stream.
   */
  class AesCtrHmacStreamDecrypter implements StreamSegmentDecrypter {
    private SecretKeySpec keySpec;
    private SecretKeySpec hmacKeySpec;
    private Cipher cipher;
    private Mac mac;
    private byte[] noncePrefix;

    AesCtrHmacStreamDecrypter() {};

    @Override
    public synchronized void init(ByteBuffer header, byte[] aad) throws GeneralSecurityException {
      if (header.remaining() != headerLength()) {
        throw new InvalidAlgorithmParameterException("Invalid header length");
      }
      byte firstByte = header.get();
      if (firstByte != headerLength()) {
        // We expect the first byte to be the length of the header.
        // If this is not the case then either the ciphertext is incorrectly
        // aligned or invalid.
        throw new GeneralSecurityException("Invalid ciphertext");
      }
      noncePrefix = new byte[NONCE_PREFIX_IN_BYTES];
      byte[] salt = new byte[keySizeInBytes];
      header.get(salt);
      header.get(noncePrefix);
      byte[] keymaterial = deriveKeyMaterial(salt, aad);
      keySpec = deriveKeySpec(keymaterial);
      hmacKeySpec = deriveHmacKeySpec(keymaterial);
      cipher = cipherInstance();
      mac = macInstance();
    }

    @Override
    public synchronized void decryptSegment(
        ByteBuffer ciphertext, int segmentNr, boolean isLastSegment, ByteBuffer plaintext)
        throws GeneralSecurityException {
      int position = ciphertext.position();
      byte[] nonce = nonceForSegment(noncePrefix, segmentNr, isLastSegment);
      int ctLength = ciphertext.remaining();
      if (ctLength < tagSizeInBytes) {
        throw new GeneralSecurityException("Ciphertext too short");
      }
      int ptLength = ctLength - tagSizeInBytes;
      int startOfTag = position + ptLength;
      ByteBuffer ct = ciphertext.duplicate();
      ct.limit(startOfTag);
      ByteBuffer tagBuffer = ciphertext.duplicate();
      tagBuffer.position(startOfTag);

      assert mac != null;
      assert hmacKeySpec != null;
      mac.init(hmacKeySpec);
      mac.update(nonce);
      mac.update(ct);
      byte[] tag = mac.doFinal();
      tag = Arrays.copyOf(tag, tagSizeInBytes);
      byte[] expectedTag = new byte[tagSizeInBytes];
      assert tagBuffer.remaining() == tagSizeInBytes;
      tagBuffer.get(expectedTag);
      assert expectedTag.length == tag.length;
      if (!SubtleUtil.arrayEquals(expectedTag, tag)) {
        throw new GeneralSecurityException("Tag mismatch");
      }

      ciphertext.limit(startOfTag);
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(nonce));
      cipher.doFinal(ciphertext, plaintext);
    }
  }
}
