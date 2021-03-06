// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote;

import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamBlockingStub;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.Digests.ActionKey;
import com.google.devtools.build.lib.remote.TreeNodeRepository.TreeNode;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.remoteexecution.v1test.ActionCacheGrpc;
import com.google.devtools.remoteexecution.v1test.ActionCacheGrpc.ActionCacheBlockingStub;
import com.google.devtools.remoteexecution.v1test.ActionResult;
import com.google.devtools.remoteexecution.v1test.BatchUpdateBlobsRequest;
import com.google.devtools.remoteexecution.v1test.BatchUpdateBlobsResponse;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.Directory;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsRequest;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsResponse;
import com.google.devtools.remoteexecution.v1test.GetActionResultRequest;
import com.google.devtools.remoteexecution.v1test.OutputDirectory;
import com.google.devtools.remoteexecution.v1test.OutputFile;
import com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A RemoteActionCache implementation that uses gRPC calls to a remote cache server. */
@ThreadSafe
public class GrpcActionCache implements RemoteActionCache {
  private final RemoteOptions options;
  private final ChannelOptions channelOptions;
  private final Channel channel;

  @VisibleForTesting
  public GrpcActionCache(Channel channel, ChannelOptions channelOptions, RemoteOptions options) {
    this.options = options;
    this.channelOptions = channelOptions;
    this.channel = channel;
  }

  // All gRPC stubs are reused.
  private final Supplier<ContentAddressableStorageBlockingStub> casBlockingStub =
      Suppliers.memoize(
          new Supplier<ContentAddressableStorageBlockingStub>() {
            @Override
            public ContentAddressableStorageBlockingStub get() {
              return ContentAddressableStorageGrpc.newBlockingStub(channel)
                  .withCallCredentials(channelOptions.getCallCredentials())
                  .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
            }
          });

  private final Supplier<ByteStreamBlockingStub> bsBlockingStub =
      Suppliers.memoize(
          new Supplier<ByteStreamBlockingStub>() {
            @Override
            public ByteStreamBlockingStub get() {
              return ByteStreamGrpc.newBlockingStub(channel)
                  .withCallCredentials(channelOptions.getCallCredentials())
                  .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
            }
          });

  private final Supplier<ByteStreamStub> bsStub =
      Suppliers.memoize(
          new Supplier<ByteStreamStub>() {
            @Override
            public ByteStreamStub get() {
              return ByteStreamGrpc.newStub(channel)
                  .withCallCredentials(channelOptions.getCallCredentials())
                  .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
            }
          });

  private final Supplier<ActionCacheBlockingStub> acBlockingStub =
      Suppliers.memoize(
          new Supplier<ActionCacheBlockingStub>() {
            @Override
            public ActionCacheBlockingStub get() {
              return ActionCacheGrpc.newBlockingStub(channel)
                  .withCallCredentials(channelOptions.getCallCredentials())
                  .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
            }
          });

  public static boolean isRemoteCacheOptions(RemoteOptions options) {
    return options.remoteCache != null;
  }

  private ImmutableSet<Digest> getMissingDigests(Iterable<Digest> digests) {
    FindMissingBlobsRequest.Builder request =
        FindMissingBlobsRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .addAllBlobDigests(digests);
    if (request.getBlobDigestsCount() == 0) {
      return ImmutableSet.of();
    }
    FindMissingBlobsResponse response = casBlockingStub.get().findMissingBlobs(request.build());
    return ImmutableSet.copyOf(response.getMissingBlobDigestsList());
  }

  /**
   * Upload enough of the tree metadata and data into remote cache so that the entire tree can be
   * reassembled remotely using the root digest.
   */
  @Override
  public void uploadTree(TreeNodeRepository repository, Path execRoot, TreeNode root)
      throws IOException, InterruptedException {
    repository.computeMerkleDigests(root);
    // TODO(olaola): avoid querying all the digests, only ask for novel subtrees.
    ImmutableSet<Digest> missingDigests = getMissingDigests(repository.getAllDigests(root));

    // Only upload data that was missing from the cache.
    ArrayList<ActionInput> actionInputs = new ArrayList<>();
    ArrayList<Directory> treeNodes = new ArrayList<>();
    repository.getDataFromDigests(missingDigests, actionInputs, treeNodes);

    if (!treeNodes.isEmpty()) {
      // TODO(olaola): split this into multiple requests if total size is > 10MB.
      BatchUpdateBlobsRequest.Builder treeBlobRequest =
          BatchUpdateBlobsRequest.newBuilder().setInstanceName(options.remoteInstanceName);
      for (Directory d : treeNodes) {
        final byte[] data = d.toByteArray();
        treeBlobRequest
            .addRequestsBuilder()
            .setContentDigest(Digests.computeDigest(data))
            .setData(ByteString.copyFrom(data));
      }
      BatchUpdateBlobsResponse response =
          casBlockingStub.get().batchUpdateBlobs(treeBlobRequest.build());
      // TODO(olaola): handle retries on transient errors.
      for (BatchUpdateBlobsResponse.Response r : response.getResponsesList()) {
        if (!Status.fromCodeValue(r.getStatus().getCode()).isOk()) {
          throw StatusProto.toStatusRuntimeException(r.getStatus());
        }
      }
    }
    if (!actionInputs.isEmpty()) {
      uploadChunks(
          actionInputs.size(),
          new Chunker.Builder()
              .addAllInputs(actionInputs, repository.getInputFileCache(), execRoot)
              .onlyUseDigests(missingDigests)
              .build());
    }
  }

  /**
   * Download the entire tree data rooted by the given digest and write it into the given location.
   */
  @Override
  public void downloadTree(Digest rootDigest, Path rootLocation)
      throws IOException, CacheNotFoundException {
    throw new UnsupportedOperationException();
  }

  /**
   * Download all results of a remotely executed action locally. TODO(olaola): will need to amend to
   * include the {@link com.google.devtools.build.lib.remote.TreeNodeRepository} for updating.
   */
  @Override
  public void downloadAllResults(ActionResult result, Path execRoot)
      throws IOException, CacheNotFoundException {
    if (result.getOutputFilesList().isEmpty() && result.getOutputDirectoriesList().isEmpty()) {
      return;
    }
    for (OutputFile file : result.getOutputFilesList()) {
      Path path = execRoot.getRelative(file.getPath());
      FileSystemUtils.createDirectoryAndParents(path.getParentDirectory());
      Digest digest = file.getDigest();
      if (digest.getSizeBytes() == 0) {
        // Handle empty file locally.
        FileSystemUtils.writeContent(path, new byte[0]);
      } else {
        try (OutputStream stream = path.getOutputStream()) {
          if (!file.getContent().isEmpty()) {
            file.getContent().writeTo(stream);
          } else {
            Iterator<ReadResponse> replies = readBlob(digest);
            while (replies.hasNext()) {
              replies.next().getData().writeTo(stream);
            }
          }
        }
      }
      path.setExecutable(file.getIsExecutable());
    }
    for (OutputDirectory directory : result.getOutputDirectoriesList()) {
      downloadTree(directory.getDigest(), execRoot.getRelative(directory.getPath()));
    }
  }

  private Iterator<ReadResponse> readBlob(Digest digest) throws CacheNotFoundException {
    String resourceName = "";
    if (!options.remoteInstanceName.isEmpty()) {
      resourceName += options.remoteInstanceName + "/";
    }
    resourceName += "blobs/" + digest.getHash() + "/" + digest.getSizeBytes();
    try {
      return bsBlockingStub
          .get()
          .read(ReadRequest.newBuilder().setResourceName(resourceName).build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new CacheNotFoundException(digest);
      }
      throw e;
    }
  }

  /** Upload all results of a locally executed action to the cache. */
  @Override
  public void uploadAllResults(Path execRoot, Collection<Path> files, ActionResult.Builder result)
      throws IOException, InterruptedException {
    ArrayList<Digest> digests = new ArrayList<>();
    Chunker.Builder b = new Chunker.Builder();
    for (Path file : files) {
      if (!file.exists()) {
        // We ignore requested results that have not been generated by the action.
        continue;
      }
      if (file.isDirectory()) {
        // TODO(olaola): to implement this for a directory, will need to create or pass a
        // TreeNodeRepository to call uploadTree.
        throw new UnsupportedOperationException("Storing a directory is not yet supported.");
      }
      digests.add(Digests.computeDigest(file));
      b.addInput(file);
    }
    ImmutableSet<Digest> missing = getMissingDigests(digests);
    if (!missing.isEmpty()) {
      uploadChunks(missing.size(), b.onlyUseDigests(missing).build());
    }
    int index = 0;
    for (Path file : files) {
      // Add to protobuf.
      // TODO(olaola): inline small results here.
      result
          .addOutputFilesBuilder()
          .setPath(file.relativeTo(execRoot).getPathString())
          .setDigest(digests.get(index++))
          .setIsExecutable(file.isExecutable());
    }
  }

  /**
   * Put the file contents cache if it is not already in it. No-op if the file is already stored in
   * cache. The given path must be a full absolute path.
   *
   * @return The key for fetching the file contents blob from cache.
   */
  @Override
  public Digest uploadFileContents(Path file) throws IOException, InterruptedException {
    Digest digest = Digests.computeDigest(file);
    ImmutableSet<Digest> missing = getMissingDigests(ImmutableList.of(digest));
    if (!missing.isEmpty()) {
      uploadChunks(1, Chunker.from(file));
    }
    return digest;
  }

  /**
   * Put the file contents cache if it is not already in it. No-op if the file is already stored in
   * cache. The given path must be a full absolute path.
   *
   * @return The key for fetching the file contents blob from cache.
   */
  @Override
  public Digest uploadFileContents(
      ActionInput input, Path execRoot, ActionInputFileCache inputCache)
      throws IOException, InterruptedException {
    Digest digest = Digests.getDigestFromInputCache(input, inputCache);
    ImmutableSet<Digest> missing = getMissingDigests(ImmutableList.of(digest));
    if (!missing.isEmpty()) {
      uploadChunks(1, Chunker.from(input, inputCache, execRoot));
    }
    return digest;
  }

  private void uploadChunks(int numItems, Chunker chunker)
      throws InterruptedException, IOException {
    final CountDownLatch finishLatch = new CountDownLatch(numItems);
    final AtomicReference<RuntimeException> exception = new AtomicReference<>(null);
    StreamObserver<WriteRequest> requestObserver = null;
    String resourceName = "";
    if (!options.remoteInstanceName.isEmpty()) {
      resourceName += options.remoteInstanceName + "/";
    }
    while (chunker.hasNext()) {
      Chunker.Chunk chunk = chunker.next();
      final Digest digest = chunk.getDigest();
      long offset = chunk.getOffset();
      WriteRequest.Builder request = WriteRequest.newBuilder();
      if (offset == 0) { // Beginning of new upload.
        numItems--;
        request.setResourceName(
            resourceName
                + "uploads/"
                + UUID.randomUUID()
                + "/blobs/"
                + digest.getHash()
                + "/"
                + digest.getSizeBytes());
        // The batches execute simultaneously.
        requestObserver =
            bsStub
                .get()
                .write(
                    new StreamObserver<WriteResponse>() {
                      private long bytesLeft = digest.getSizeBytes();

                      @Override
                      public void onNext(WriteResponse reply) {
                        bytesLeft -= reply.getCommittedSize();
                      }

                      @Override
                      public void onError(Throwable t) {
                        exception.compareAndSet(
                            null, new StatusRuntimeException(Status.fromThrowable(t)));
                        finishLatch.countDown();
                      }

                      @Override
                      public void onCompleted() {
                        if (bytesLeft != 0) {
                          exception.compareAndSet(
                              null, new RuntimeException("Server did not commit all data."));
                        }
                        finishLatch.countDown();
                      }
                    });
      }
      byte[] data = chunk.getData();
      boolean finishWrite = offset + data.length == digest.getSizeBytes();
      request.setData(ByteString.copyFrom(data)).setWriteOffset(offset).setFinishWrite(finishWrite);
      requestObserver.onNext(request.build());
      if (finishWrite) {
        requestObserver.onCompleted();
      }
      if (finishLatch.getCount() <= numItems) {
        // Current RPC errored before we finished sending.
        if (!finishWrite) {
          chunker.advanceInput();
        }
      }
    }
    finishLatch.await(options.remoteTimeout, TimeUnit.SECONDS);
    if (exception.get() != null) {
      throw exception.get(); // Re-throw the first encountered exception.
    }
  }

  @Override
  public Digest uploadBlob(byte[] blob) throws InterruptedException {
    Digest digest = Digests.computeDigest(blob);
    ImmutableSet<Digest> missing = getMissingDigests(ImmutableList.of(digest));
    try {
      if (!missing.isEmpty()) {
        uploadChunks(1, Chunker.from(blob));
      }
      return digest;
    } catch (IOException e) {
      // This will never happen.
      throw new RuntimeException();
    }
  }

  @Override
  public byte[] downloadBlob(Digest digest) throws CacheNotFoundException {
    if (digest.getSizeBytes() == 0) {
      return new byte[0];
    }
    Iterator<ReadResponse> replies = readBlob(digest);
    byte[] result = new byte[(int) digest.getSizeBytes()];
    int offset = 0;
    while (replies.hasNext()) {
      ByteString data = replies.next().getData();
      data.copyTo(result, offset);
      offset += data.size();
    }
    Preconditions.checkState(digest.getSizeBytes() == offset);
    return result;
  }

  // Execution Cache API

  /** Returns a cached result for a given Action digest, or null if not found in cache. */
  @Override
  public ActionResult getCachedActionResult(ActionKey actionKey) {
    try {
      return acBlockingStub
          .get()
          .getActionResult(
              GetActionResultRequest.newBuilder()
                  .setInstanceName(options.remoteInstanceName)
                  .setActionDigest(actionKey.getDigest())
                  .build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return null;
      }
      throw e;
    }
  }

  /** Sets the given result as result of the given Action. */
  @Override
  public void setCachedActionResult(ActionKey actionKey, ActionResult result)
      throws InterruptedException {
    try {
      acBlockingStub
          .get()
          .updateActionResult(
              UpdateActionResultRequest.newBuilder()
                  .setInstanceName(options.remoteInstanceName)
                  .setActionDigest(actionKey.getDigest())
                  .setActionResult(result)
                  .build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() != Status.Code.UNIMPLEMENTED) {
        throw e;
      }
    }
  }
}
