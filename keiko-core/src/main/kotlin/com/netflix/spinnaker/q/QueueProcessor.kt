/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.q

import com.netflix.spinnaker.q.metrics.EventPublisher
import com.netflix.spinnaker.q.metrics.NoHandlerCapacity
import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.RejectedExecutionException
import javax.annotation.PostConstruct

/**
 * The processor that fetches messages from the [Queue] and hands them off to
 * the appropriate [MessageHandler].
 */
class QueueProcessor(
  private val queue: Queue,
  private val queueExecutor: QueueExecutor,
  private val handlers: Collection<MessageHandler<*>>,
  private val activator: Activator,
  private val publisher: EventPublisher
) {
  private val log: Logger = getLogger(javaClass)

  /**
   * Polls the [Queue] once to attempt to read a single message so long as
   * [queueExecutor] has capacity.
   */
  @Scheduled(fixedDelayString = "\${queue.poll.frequency.ms:10}")
  fun pollOnce() =
    activator.ifEnabled {
      if (queueExecutor.hasCapacity()) {
        queue.poll { message, ack ->
          log.info("Received message $message")
          val handler = handlerFor(message)
          if (handler != null) {
            try {
              queueExecutor.executor.execute {
                handler.invoke(message)
                ack.invoke()
              }
            } catch (e: RejectedExecutionException) {
              log.warn("Executor at capacity, immediately re-queuing message", e)
              queue.push(message)
            }
          } else {
            // TODO: DLQ
            throw IllegalStateException("Unsupported message type ${message.javaClass.simpleName}: $message")
          }
        }
      } else {
        publisher.publishEvent(NoHandlerCapacity())
      }
    }

  private val handlerCache = mutableMapOf<Class<out Message>, MessageHandler<*>>()

  private fun handlerFor(message: Message) =
    handlerCache[message.javaClass]
      .let { handler ->
        handler ?: handlers
          .find { it.messageType.isAssignableFrom(message.javaClass) }
          ?.also { handlerCache[message.javaClass] = it }
      }

  @PostConstruct
  fun confirmQueueType() =
    log.info("Using ${queue.javaClass.simpleName} queue")
}
