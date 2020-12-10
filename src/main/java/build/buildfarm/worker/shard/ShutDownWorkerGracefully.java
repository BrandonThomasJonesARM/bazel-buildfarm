// Copyright 2020 The Bazel Authors. All rights reserved.
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

package build.buildfarm.worker.shard;

import build.buildfarm.v1test.DrainWorkerPipelineRequest;
import build.buildfarm.v1test.DrainWorkerPipelineResults;
import build.buildfarm.v1test.ShutDownWorkerGracefullyGrpc;
import io.grpc.stub.StreamObserver;

public class ShutDownWorkerGracefully
    extends ShutDownWorkerGracefullyGrpc.ShutDownWorkerGracefullyImplBase {
  private final Worker worker;

  public ShutDownWorkerGracefully(Worker worker) {
    this.worker = worker;
  }

  @Override
  public void drainWorkerPipeline(
      DrainWorkerPipelineRequest request,
      StreamObserver<DrainWorkerPipelineResults> responseObserver) {
    try {
      responseObserver.onNext(
          DrainWorkerPipelineResults.newBuilder()
              .setIsPipelineEmpty(worker.shutDownWorkerGracefully())
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }
}
