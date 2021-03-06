/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.request.s3.bucket;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.om.ratis.utils.OzoneManagerDoubleBufferHelper;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hdds.protocol.StorageType;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.audit.OMAction;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OMMetrics;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.request.volume.OMVolumeRequest;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.response.bucket.OMBucketCreateResponse;
import org.apache.hadoop.ozone.om.response.s3.bucket.S3BucketCreateResponse;
import org.apache.hadoop.ozone.om.response.volume.OMVolumeCreateResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .S3CreateBucketRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .S3CreateBucketResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .S3CreateVolumeInfo;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.UserVolumeInfo;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.hdds.utils.db.cache.CacheKey;
import org.apache.hadoop.hdds.utils.db.cache.CacheValue;

import static org.apache.hadoop.ozone.OzoneConsts.OM_S3_VOLUME_PREFIX;
import static org.apache.hadoop.ozone.OzoneConsts.S3_BUCKET_MAX_LENGTH;
import static org.apache.hadoop.ozone.OzoneConsts.S3_BUCKET_MIN_LENGTH;
import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.BUCKET_LOCK;
import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.S3_BUCKET_LOCK;
import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.USER_LOCK;
import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.VOLUME_LOCK;

/**
 * Handles S3 Bucket create request.
 */
public class S3BucketCreateRequest extends OMVolumeRequest {

  private static final String S3_ADMIN_NAME = "OzoneS3Manager";

  private static final Logger LOG =
      LoggerFactory.getLogger(S3CreateBucketRequest.class);

  public S3BucketCreateRequest(OMRequest omRequest) {
    super(omRequest);
  }

  @Override
  public OMRequest preExecute(OzoneManager ozoneManager) throws IOException {
    S3CreateBucketRequest s3CreateBucketRequest =
        getOmRequest().getCreateS3BucketRequest();
    Preconditions.checkNotNull(s3CreateBucketRequest);

    S3CreateBucketRequest.Builder newS3CreateBucketRequest =
        s3CreateBucketRequest.toBuilder().setS3CreateVolumeInfo(
            S3CreateVolumeInfo.newBuilder().setCreationTime(Time.now()));

    // TODO: Do we need to enforce the bucket rules in this code path?
    // https://docs.aws.amazon.com/AmazonS3/latest/dev/BucketRestrictions.html

    // For now only checked the length.
    int bucketLength = s3CreateBucketRequest.getS3Bucketname().length();
    if (bucketLength < S3_BUCKET_MIN_LENGTH ||
        bucketLength >= S3_BUCKET_MAX_LENGTH) {
      throw new OMException("S3BucketName must be at least 3 and not more " +
          "than 63 characters long",
          OMException.ResultCodes.S3_BUCKET_INVALID_LENGTH);
    }

    return getOmRequest().toBuilder()
        .setCreateS3BucketRequest(newS3CreateBucketRequest)
        .setUserInfo(getUserInfo()).build();
  }

  @Override
  public OMClientResponse validateAndUpdateCache(OzoneManager ozoneManager,
      long trxnLogIndex, OzoneManagerDoubleBufferHelper omDoubleBufferHelper) {

    S3CreateBucketRequest s3CreateBucketRequest = getOmRequest()
        .getCreateS3BucketRequest();
    String userName = s3CreateBucketRequest.getUserName();
    String s3BucketName = s3CreateBucketRequest.getS3Bucketname();

    OMResponse.Builder omResponse = OMResponse.newBuilder().setCmdType(
        OzoneManagerProtocolProtos.Type.CreateS3Bucket).setStatus(
        OzoneManagerProtocolProtos.Status.OK).setSuccess(true);

    OMMetrics omMetrics = ozoneManager.getMetrics();
    omMetrics.incNumS3BucketCreates();

    // When s3 Bucket is created, we internally create ozone volume/ozone
    // bucket. Ozone volume name is generated from userName by calling
    // formatOzoneVolumeName. Ozone bucket name is same as s3 bucket name.
    // In S3 buckets are unique, so we create a mapping like s3BucketName ->
    // ozoneVolume/ozoneBucket and add it to s3 mapping table. If
    // s3BucketName exists in mapping table, bucket already exist or we go
    // ahead and create a bucket.
    OMMetadataManager omMetadataManager = ozoneManager.getMetadataManager();
    IOException exception = null;

    boolean volumeCreated = false, acquiredVolumeLock = false,
        acquiredUserLock = false, acquiredS3Lock = false, success = true;
    String volumeName = formatOzoneVolumeName(userName);
    OMClientResponse omClientResponse = null;

    try {
      // TODO to support S3 ACL later.
      acquiredS3Lock = omMetadataManager.getLock().acquireWriteLock(
          S3_BUCKET_LOCK, s3BucketName);

      // First check if this s3Bucket exists in S3 table
      if (omMetadataManager.getS3Table().isExist(s3BucketName)) {
        // Check if Bucket exists in Bucket Table
        String omBucketKey = omMetadataManager.getBucketKey(volumeName,
            s3BucketName);
        OmBucketInfo dbBucketInfo = omMetadataManager.getBucketTable()
            .get(omBucketKey);
        if (dbBucketInfo != null) {
          // Check if this transaction is a replay of ratis logs.
          if (isReplay(ozoneManager, dbBucketInfo.getUpdateID(),
              trxnLogIndex)) {
            // Replay implies the response has already been returned to
            // the client. So take no further action and return a dummy
            // OMClientResponse.
            LOG.debug("Replayed Transaction {} ignored. Request: {}",
                trxnLogIndex, s3CreateBucketRequest);
            return new S3BucketCreateResponse(
                createReplayOMResponse(omResponse));
          }
        } else {
          throw new OMException("S3Bucket " + s3BucketName + " mapping " +
              "already exists in S3 table but Bucket does not exist.",
              OMException.ResultCodes.S3_BUCKET_ALREADY_EXISTS);
        }
        throw new OMException("S3Bucket " + s3BucketName + " already exists",
            OMException.ResultCodes.S3_BUCKET_ALREADY_EXISTS);
      }

      OMVolumeCreateResponse omVolumeCreateResponse = null;
      try {
        acquiredVolumeLock = omMetadataManager.getLock().acquireWriteLock(
            VOLUME_LOCK, volumeName);
        acquiredUserLock = omMetadataManager.getLock().acquireWriteLock(
            USER_LOCK, userName);
        // Check if volume exists, if it does not exist create ozone volume.
        String volumeKey = omMetadataManager.getVolumeKey(volumeName);
        if (!omMetadataManager.getVolumeTable().isExist(volumeKey)) {
          // A replay transaction can reach here only if the volume has been
          // deleted in later transactions. Hence, we can continue with this
          // request irrespective of whether it is a replay or not.
          OmVolumeArgs omVolumeArgs = createOmVolumeArgs(volumeName, userName,
              s3CreateBucketRequest.getS3CreateVolumeInfo().getCreationTime(),
              trxnLogIndex);
          UserVolumeInfo volumeList = omMetadataManager.getUserTable().get(
              omMetadataManager.getUserKey(userName));
          volumeList = addVolumeToOwnerList(volumeList, volumeName, userName,
              ozoneManager.getMaxUserVolumeCount(), trxnLogIndex);
          createVolume(omMetadataManager, omVolumeArgs, volumeList, volumeKey,
              omMetadataManager.getUserKey(userName), trxnLogIndex);
          volumeCreated = true;
          omVolumeCreateResponse = new OMVolumeCreateResponse(
              omResponse.build(), omVolumeArgs, volumeList);
        }
      } finally {
        if (acquiredUserLock) {
          omMetadataManager.getLock().releaseWriteLock(USER_LOCK, userName);
        }
        if (acquiredVolumeLock) {
          omMetadataManager.getLock().releaseWriteLock(VOLUME_LOCK, volumeName);
        }
      }

      // check if ozone bucket exists, if not create ozone bucket
      OmBucketInfo omBucketInfo = createBucket(ozoneManager, volumeName,
          s3BucketName, userName, s3CreateBucketRequest.getS3CreateVolumeInfo()
              .getCreationTime(), trxnLogIndex);

      // Now finally add it to s3 table cache.
      omMetadataManager.getS3Table().addCacheEntry(
          new CacheKey<>(s3BucketName), new CacheValue<>(
              Optional.of(formatS3MappingName(volumeName, s3BucketName)),
              trxnLogIndex));

      OMBucketCreateResponse omBucketCreateResponse =
          new OMBucketCreateResponse(omResponse.build(), omBucketInfo);

      omClientResponse = new S3BucketCreateResponse(
          omResponse.setCreateS3BucketResponse(
              S3CreateBucketResponse.newBuilder()).build(),
          omVolumeCreateResponse, omBucketCreateResponse, s3BucketName,
          formatS3MappingName(volumeName, s3BucketName));
    } catch (IOException ex) {
      success = false;
      exception = ex;
      omClientResponse = new S3BucketCreateResponse(
          createErrorOMResponse(omResponse, exception));
    } finally {
      if (omClientResponse != null) {
        omClientResponse.setFlushFuture(omDoubleBufferHelper.add(
            omClientResponse, trxnLogIndex));
      }
      if (acquiredS3Lock) {
        omMetadataManager.getLock().releaseWriteLock(
            S3_BUCKET_LOCK, s3BucketName);
      }
    }

    // Performing audit logging outside of the lock.
    auditLog(ozoneManager.getAuditLogger(), buildAuditMessage(
        OMAction.CREATE_S3_BUCKET, buildAuditMap(userName, s3BucketName),
        exception, getOmRequest().getUserInfo()));

    if (success) {
      LOG.debug("S3Bucket is successfully created for userName: {}, " +
          "s3BucketName {}, volumeName {}", userName, s3BucketName, volumeName);
      updateMetrics(omMetrics, volumeCreated);
    } else {
      LOG.error("S3Bucket Creation Failed for userName: {}, s3BucketName {}, " +
          "VolumeName {}", userName, s3BucketName, volumeName);
      omMetrics.incNumS3BucketCreateFails();
    }
    return omClientResponse;
  }

  private OmBucketInfo createBucket(OzoneManager ozoneManager,
      String volumeName, String s3BucketName, String userName,
      long creationTime, long transactionLogIndex) throws IOException {
    OMMetadataManager omMetadataManager = ozoneManager.getMetadataManager();
    // check if ozone bucket exists, if it does not exist create ozone
    // bucket
    boolean acquireBucketLock = false;
    OmBucketInfo omBucketInfo = null;
    try {
      acquireBucketLock =
          omMetadataManager.getLock().acquireWriteLock(BUCKET_LOCK, volumeName,
              s3BucketName);
      String bucketKey = omMetadataManager.getBucketKey(volumeName,
          s3BucketName);
      if (!omMetadataManager.getBucketTable().isExist(bucketKey)) {
        omBucketInfo = createOmBucketInfo(volumeName, s3BucketName, userName,
            creationTime, transactionLogIndex);
        // Add to bucket table cache.
        omMetadataManager.getBucketTable().addCacheEntry(
            new CacheKey<>(bucketKey),
            new CacheValue<>(Optional.of(omBucketInfo), transactionLogIndex));
      } else {
        // This can happen when a ozone bucket exists already in the
        // volume, but this is not a s3 bucket.
        throw new OMException("Bucket " + s3BucketName + " already exists",
            OMException.ResultCodes.BUCKET_ALREADY_EXISTS);
      }
    } finally {
      if (acquireBucketLock) {
        omMetadataManager.getLock().releaseWriteLock(BUCKET_LOCK, volumeName,
            s3BucketName);
      }
    }
    return omBucketInfo;
  }

  /**
   * Increment OMMetrics on success.
   */
  private static void updateMetrics(OMMetrics omMetrics,
      boolean isVolumeCreated) {
    if (isVolumeCreated) {
      omMetrics.incNumVolumes();
    }
    omMetrics.incNumBuckets();
    omMetrics.incNumS3Buckets();
  }

  /**
   * Generate Ozone volume name from userName.
   * @param userName
   * @return volume name
   */
  @VisibleForTesting
  public static String formatOzoneVolumeName(String userName) {
    return String.format(OM_S3_VOLUME_PREFIX + "%s", userName);
  }

  /**
   * Generate S3Mapping for provided volume and bucket. This information will
   * be persisted in s3 table in OM DB.
   * @param volumeName
   * @param bucketName
   * @return s3Mapping
   */
  @VisibleForTesting
  public static String formatS3MappingName(String volumeName,
      String bucketName) {
    return String.format("%s" + OzoneConsts.OM_KEY_PREFIX + "%s", volumeName,
        bucketName);
  }

  /**
   * Create {@link OmVolumeArgs} which needs to be persisted in volume table
   * in OM DB.
   * @param volumeName
   * @param userName
   * @param creationTime
   * @return {@link OmVolumeArgs}
   */
  private OmVolumeArgs createOmVolumeArgs(String volumeName, String userName,
      long creationTime, long transactionLogIndex) throws IOException {
    OmVolumeArgs.Builder builder = OmVolumeArgs.newBuilder()
        .setAdminName(S3_ADMIN_NAME).setVolume(volumeName)
        .setQuotaInBytes(OzoneConsts.MAX_QUOTA_IN_BYTES)
        .setOwnerName(userName)
        .setCreationTime(creationTime)
        .setObjectID(transactionLogIndex)
        .setUpdateID(transactionLogIndex);

    // Set default acls.
    for (OzoneAcl acl : getDefaultAcls(userName)) {
      builder.addOzoneAcls(OzoneAcl.toProtobuf(acl));
    }

    return builder.build();
  }

  /**
   * Create {@link OmBucketInfo} which needs to be persisted in to bucket table
   * in OM DB.
   * @param volumeName
   * @param s3BucketName
   * @param creationTime
   * @return {@link OmBucketInfo}
   */
  private OmBucketInfo createOmBucketInfo(String volumeName,
      String s3BucketName, String userName, long creationTime,
      long transactionLogIndex) {
    //TODO: Now S3Bucket API takes only bucketName as param. In future if we
    // support some configurable options we need to fix this.
    OmBucketInfo.Builder builder = OmBucketInfo.newBuilder()
        .setVolumeName(volumeName)
        .setBucketName(s3BucketName)
        .setIsVersionEnabled(Boolean.FALSE)
        .setStorageType(StorageType.DEFAULT)
        .setCreationTime(creationTime)
        .setObjectID(transactionLogIndex)
        .setUpdateID(transactionLogIndex);

    // Set default acls.
    builder.setAcls(getDefaultAcls(userName));

    return builder.build();
  }

  /**
   * Build auditMap.
   * @param userName
   * @param s3BucketName
   * @return auditMap
   */
  private Map<String, String> buildAuditMap(String userName,
      String s3BucketName) {
    Map<String, String> auditMap = new HashMap<>();
    auditMap.put(userName, OzoneConsts.USERNAME);
    auditMap.put(s3BucketName, OzoneConsts.S3_BUCKET);
    return auditMap;
  }

  /**
   * Get default acls.
   * */
  private List<OzoneAcl> getDefaultAcls(String userName) {
    UserGroupInformation ugi = createUGI();
    return OzoneAcl.parseAcls("user:" + (ugi == null ? userName :
        ugi.getUserName()) + ":a,user:" + S3_ADMIN_NAME + ":a");
  }
}

