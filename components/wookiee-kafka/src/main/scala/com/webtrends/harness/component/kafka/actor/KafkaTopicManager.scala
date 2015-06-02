/*
 * Copyright 2015 Webtrends (http://www.webtrends.com)
 *
 * See the LICENCE.txt file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webtrends.harness.component.kafka.actor

import akka.actor.{Actor, Props}
import com.webtrends.harness.component.kafka.KafkaConsumerCoordinator.TopicPartitionResp
import com.webtrends.harness.component.kafka.actor.AssignmentDistributorLeader.PartitionAssignment
import com.webtrends.harness.component.kafka.util.KafkaSettings
import com.webtrends.harness.component.zookeeper.{ZookeeperAdapter, ZookeeperEventAdapter}
import com.webtrends.harness.health.{ComponentState, HealthComponent}
import com.webtrends.harness.logging.ActorLoggingAdapter
import kafka.api.TopicMetadataRequest
import kafka.consumer.SimpleConsumer

import scala.collection.mutable
import scala.language.postfixOps

object KafkaTopicManager {
  /**
   * BrokerSpec maps the JSON used by Kafka 0.8 to describe a broker as
   * written by it in Zookeeper.
   * @param host The broker hostname.
   * @param port The broker port.
   * @param cluster The zookeeper cluster this broker belongs to
   */
  case class BrokerSpec(host: String, port: Int, cluster: String)

  case object TopicPartitionReq

  def props() = Props[KafkaTopicManager]
}

class KafkaTopicManager extends Actor with KafkaSettings
with ActorLoggingAdapter with ZookeeperAdapter with ZookeeperEventAdapter {
  import KafkaTopicManager._

  val actorName = "Kafka Topic Manager"

  // Holder of consumers connected to each kafka broker
  val consumersByHost = new mutable.HashMap[String, SimpleConsumer]()

  context.parent ! HealthComponent(actorName, ComponentState.NORMAL, "Proxy has been started")

  def receive: Receive = configReceive orElse {
    case TopicPartitionReq =>
      sender ! TopicPartitionResp(getPartitionLeaders)
  }

  def getPartitionLeaders: Set[PartitionAssignment] = {
    val partitionsByTopic = new mutable.HashSet[PartitionAssignment]()
    val topicMetaRequest = new TopicMetadataRequest(versionId = 1, correlationId = 0, clientId = clientId, topics = Seq())

    // Get our partition meta data for the configured topics
    val processedClusters = new mutable.HashSet[String]()
    val brokers = kafkaSources
    for (bro <- brokers.values
         if !processedClusters.contains(bro.cluster)) {
      try {
        if (!consumersByHost.contains(bro.host)) {
          consumersByHost.put(bro.host, new SimpleConsumer(bro.host, bro.port, 15000, bufferSize, clientId))
        }
        val consumer = consumersByHost(bro.host)
        val topicsMetaResp = consumer.send(topicMetaRequest)
        for ( topicMeta <- topicsMetaResp.topicsMetadata.filter { meta => topicMap.keys.toList.contains(meta.topic) };
              partMeta <- topicMeta.partitionsMetadata )
          yield {
            partMeta.leader match {
              case Some(broker) =>
                log.debug(s"Leader found for topic [${topicMeta.topic}:${partMeta.partitionId}]: ${broker.host}")
                partitionsByTopic.add(PartitionAssignment(topicMeta.topic, partMeta.partitionId, brokers(broker.host).cluster, broker.host))
              case None =>
                log.error(s"No leader found for topic [${topicMeta.topic}:${partMeta.partitionId}]")
            }
            processedClusters.add(bro.cluster)
          }
      } catch {
        case e: Throwable =>
          log.error(s"Unable to get topic meta data from ${bro.host}, will retry soon", e)
          consumersByHost.remove(bro.host).foreach(_.close())
      }
    }

    val unprocClusters = brokers.filter(it => !processedClusters.contains(it._2.cluster)).map(_._2.cluster).toSet
    if (unprocClusters.nonEmpty) {
      log.warn(s"Some brokers despondent: ${unprocClusters.mkString(",")}. Remaining brokers will start their workers.")
      context.parent ! HealthComponent(actorName, ComponentState.DEGRADED, s"Brokers despondent: ${unprocClusters.mkString(",")}.")
    } else {
      log.debug("Successfully processed brokers {}", brokers.toString())
      context.parent ! HealthComponent(actorName, ComponentState.NORMAL, "Successfully fetched broker data")
    }
    partitionsByTopic.toSet[PartitionAssignment]
  }
}
