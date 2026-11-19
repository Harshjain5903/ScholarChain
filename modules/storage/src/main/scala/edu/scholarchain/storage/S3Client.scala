package edu.scholarchain.storage

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3Client => AwsS3Client}
import software.amazon.awssdk.services.s3.model.*

import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Adapter for Amazon S3 using AWS SDK v2. */
class S3StorageClient(client: AwsS3Client):

  /** Compute lowercase hex SHA-256 digest of raw bytes. */
  private def sha256Hex(bytes: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map("%02x".format(_)).mkString

  /** Write an object with SHA-256 content hash metadata. */
  def putObject(
      bucket: String,
      key: String,
      bytes: Array[Byte],
      contentType: String
  ): String =
    val hash = sha256Hex(bytes)
    val request = PutObjectRequest
      .builder()
      .bucket(bucket)
      .key(key)
      .contentType(contentType)
      .metadata(Map("content-sha256" -> hash).asJava)
      .build()
    client.putObject(request, RequestBody.fromBytes(bytes))
    hash

  /**
   * Write an object only if the existing ETag does not match the content hash. Returns the hash
   * if written, or the existing hash if skipped.
   */
  def putIfChanged(
      bucket: String,
      key: String,
      bytes: Array[Byte],
      contentType: String
  ): String =
    val hash = sha256Hex(bytes)
    val existingETag =
      try
        val headReq = HeadObjectRequest.builder().bucket(bucket).key(key).build()
        val headResp = client.headObject(headReq)
        Some(headResp.eTag().replace("\"", ""))
      catch case _: NoSuchKeyException => None
    if existingETag.contains(hash) then hash
    else putObject(bucket, key, bytes, contentType)

  /** Read an object as raw bytes. */
  def getObject(bucket: String, key: String): Array[Byte] =
    val request = GetObjectRequest.builder().bucket(bucket).key(key).build()
    val response = client.getObjectAsBytes(request)
    response.asByteArray()

  /** List object keys under a prefix. */
  def listObjects(bucket: String, prefix: String): Vector[String] =
    val request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()
    val response = client.listObjectsV2(request)
    response.contents().asScala.map(_.key()).toVector

  /** Write a JSON run manifest under the standard manifest path. */
  def putRunManifest(bucket: String, runId: String, manifest: ujson.Value): String =
    val key = s"manifests/$runId/manifest.json"
    val bytes = ujson.write(manifest, indent = 2).getBytes("UTF-8")
    putObject(bucket, key, bytes, "application/json")

  /** Generate a Neptune bulk-load manifest for files under a prefix. */
  def generateBulkLoadManifest(
      bucket: String,
      prefix: String,
      format: String
  ): ujson.Value =
    val keys = listObjects(bucket, prefix)
    val sources = keys.map { k =>
      ujson.Obj(
        "source" -> ujson.Str(s"s3://$bucket/$k"),
        "format" -> ujson.Str(format)
      )
    }
    ujson.Obj("sources" -> ujson.Arr(sources*))

object S3StorageClient:

  /** Create a client for the given region. */
  def create(region: String): S3StorageClient =
    val awsClient = AwsS3Client
      .builder()
      .region(Region.of(region))
      .build()
    S3StorageClient(awsClient)

  /** Expose SHA-256 computation for testing. */
  def sha256Hex(bytes: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map("%02x".format(_)).mkString
