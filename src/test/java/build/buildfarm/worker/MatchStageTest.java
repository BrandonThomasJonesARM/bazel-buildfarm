// Copyright 2018 The Bazel Authors. All rights reserved.
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

package build.buildfarm.worker;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata.Stage;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.instance.Instance.MatchListener;
import build.buildfarm.v1test.ExecuteEntry;
import build.buildfarm.v1test.QueueEntry;
import build.buildfarm.v1test.QueuedOperation;
import build.buildfarm.v1test.WorkerConfig;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.naming.ConfigurationException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MatchStageTest {
  static class PipelineSink extends PipelineStage {
    private static final Logger logger = Logger.getLogger(PipelineSink.class.getName());

    private final List<OperationContext> operationContexts = Lists.newArrayList();
    private final Predicate<OperationContext> onPutShouldClose;

    PipelineSink(Predicate<OperationContext> onPutShouldClose) {
      super("Sink", null, null, null);
      this.onPutShouldClose = onPutShouldClose;
    }

    public List<OperationContext> getOperationContexts() {
      return operationContexts;
    }

    @Override
    public Logger getLogger() {
      return logger;
    }

    @Override
    public void put(OperationContext operationContext) {
      operationContexts.add(operationContext);
      if (onPutShouldClose.test(operationContext)) {
        close();
      }
    }

    @Override
    public OperationContext take() {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  public void fetchFailureReentry() throws ConfigurationException {
    Map<String, QueuedOperation> queuedOperations = Maps.newHashMap();
    List<QueueEntry> queue = Lists.newArrayList();

    Poller poller = mock(Poller.class);

    AtomicInteger requeueCount = new AtomicInteger();
    WorkerContext workerContext = new StubWorkerContext() {
      @Override
      public DigestUtil getDigestUtil() {
        return null;
      }

      @Override
      public Poller createPoller(String name, QueueEntry queueEntry, Stage stage) {
        return poller;
      }

      @Override
      public void match(MatchListener listener) throws InterruptedException {
        QueueEntry queueEntry = queue.remove(0);
        listener.onEntry(queueEntry);
        listener.onOperation(queuedOperations.get(queueEntry.getExecuteEntry().getOperationName()));
      }
    };

    QueueEntry badEntry = QueueEntry.newBuilder()
        .setExecuteEntry(ExecuteEntry.newBuilder()
            .setOperationName("bad"))
        .build();
    // inspire empty argument list in Command resulting in null
    queuedOperations.put("bad", QueuedOperation.getDefaultInstance());
    queue.add(badEntry);

    QueueEntry goodEntry = QueueEntry.newBuilder()
        .setExecuteEntry(ExecuteEntry.newBuilder()
            .setOperationName("good"))
        .build();
    queuedOperations.put("good", QueuedOperation.newBuilder()
        .setAction(Action.getDefaultInstance())
        .setCommand(Command.newBuilder()
            .addArguments("/bin/true")
            .build())
        .build());
    queue.add(goodEntry);

    PipelineSink output = new PipelineSink(
        (operationContext) -> operationContext.operation.getName().equals("good"));

    PipelineStage matchStage = new MatchStage(workerContext, output, null);
    matchStage.run();
    verify(poller, times(2)).stop();
    assertThat(output.getOperationContexts().size()).isEqualTo(1);
    OperationContext operationContext = output.getOperationContexts().get(0);
    assertThat(operationContext.operation.getName()).isEqualTo("good");
  }
}
