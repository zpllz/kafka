/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.zk.migration

import kafka.api.LeaderAndIsr
import kafka.controller.{LeaderIsrAndControllerEpoch, ReplicaAssignment}
import kafka.coordinator.transaction.ProducerIdManager
import kafka.server.{ConfigType, KafkaConfig}
import org.apache.kafka.common.config.{ConfigResource, TopicConfig}
import org.apache.kafka.common.errors.ControllerMovedException
import org.apache.kafka.common.metadata.{ConfigRecord, MetadataRecordType, PartitionRecord, ProducerIdsRecord, TopicRecord}
import org.apache.kafka.common.{TopicPartition, Uuid}
import org.apache.kafka.image.{MetadataDelta, MetadataImage, MetadataProvenance}
import org.apache.kafka.metadata.migration.{KRaftMigrationZkWriter, ZkMigrationLeadershipState}
import org.apache.kafka.metadata.{LeaderRecoveryState, PartitionRegistration}
import org.apache.kafka.server.common.ApiMessageAndVersion
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows, assertTrue, fail}
import org.junit.jupiter.api.Test

import java.util.Properties
import scala.collection.Map
import scala.jdk.CollectionConverters._

/**
 * ZooKeeper integration tests that verify the interoperability of KafkaZkClient and ZkMigrationClient.
 */
class ZkMigrationClientTest extends ZkMigrationTestHarness {

  @Test
  def testMigrateEmptyZk(): Unit = {
    val brokers = new java.util.ArrayList[Integer]()
    val batches = new java.util.ArrayList[java.util.List[ApiMessageAndVersion]]()

    migrationClient.readAllMetadata(batch => batches.add(batch), brokerId => brokers.add(brokerId))
    assertEquals(0, brokers.size())
    assertEquals(0, batches.size())
  }

  @Test
  def testEmptyWrite(): Unit = {
    val (zkVersion, responses) = zkClient.retryMigrationRequestsUntilConnected(Seq(), migrationState)
    assertEquals(migrationState.migrationZkVersion(), zkVersion)
    assertTrue(responses.isEmpty)
  }

  @Test
  def testUpdateExistingPartitions(): Unit = {
    // Create a topic and partition state in ZK like KafkaController would
    val assignment = Map(
      new TopicPartition("test", 0) -> List(0, 1, 2),
      new TopicPartition("test", 1) -> List(1, 2, 3)
    )
    zkClient.createTopicAssignment("test", Some(Uuid.randomUuid()), assignment)

    val leaderAndIsrs = Map(
      new TopicPartition("test", 0) -> LeaderIsrAndControllerEpoch(
        LeaderAndIsr(0, 5, List(0, 1, 2), LeaderRecoveryState.RECOVERED, -1), 1),
      new TopicPartition("test", 1) -> LeaderIsrAndControllerEpoch(
        LeaderAndIsr(1, 5, List(1, 2, 3), LeaderRecoveryState.RECOVERED, -1), 1)
    )
    zkClient.createTopicPartitionStatesRaw(leaderAndIsrs, 0)

    // Now verify that we can update it with migration client
    assertEquals(0, migrationState.migrationZkVersion())

    val partitions = Map(
      0 -> new PartitionRegistration(Array(0, 1, 2), Array(1, 2), Array(), Array(), 1, LeaderRecoveryState.RECOVERED, 6, -1),
      1 -> new PartitionRegistration(Array(1, 2, 3), Array(3), Array(), Array(), 3, LeaderRecoveryState.RECOVERED, 7, -1)
    ).map { case (k, v) => Integer.valueOf(k) -> v }.asJava
    migrationState = migrationClient.topicClient().updateTopicPartitions(Map("test" -> partitions).asJava, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    // Read back with Zk client
    val partition0 = zkClient.getTopicPartitionState(new TopicPartition("test", 0)).get.leaderAndIsr
    assertEquals(1, partition0.leader)
    assertEquals(6, partition0.leaderEpoch)
    assertEquals(List(1, 2), partition0.isr)

    val partition1 = zkClient.getTopicPartitionState(new TopicPartition("test", 1)).get.leaderAndIsr
    assertEquals(3, partition1.leader)
    assertEquals(7, partition1.leaderEpoch)
    assertEquals(List(3), partition1.isr)

    // Delete whole topic
    migrationState = migrationClient.topicClient().deleteTopic("test", migrationState)
    assertEquals(2, migrationState.migrationZkVersion())
  }

  @Test
  def testCreateNewPartitions(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())

    val partitions = Map(
      0 -> new PartitionRegistration(Array(0, 1, 2), Array(0, 1, 2), Array(), Array(), 0, LeaderRecoveryState.RECOVERED, 0, -1),
      1 -> new PartitionRegistration(Array(1, 2, 3), Array(1, 2, 3), Array(), Array(), 1, LeaderRecoveryState.RECOVERED, 0, -1)
    ).map { case (k, v) => Integer.valueOf(k) -> v }.asJava
    migrationState = migrationClient.topicClient().createTopic("test", Uuid.randomUuid(), partitions, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    // Read back with Zk client
    val partition0 = zkClient.getTopicPartitionState(new TopicPartition("test", 0)).get.leaderAndIsr
    assertEquals(0, partition0.leader)
    assertEquals(0, partition0.leaderEpoch)
    assertEquals(List(0, 1, 2), partition0.isr)

    val partition1 = zkClient.getTopicPartitionState(new TopicPartition("test", 1)).get.leaderAndIsr
    assertEquals(1, partition1.leader)
    assertEquals(0, partition1.leaderEpoch)
    assertEquals(List(1, 2, 3), partition1.isr)
  }

  @Test
  def testIdempotentCreateTopics(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())

    val partitions = Map(
      0 -> new PartitionRegistration(Array(0, 1, 2), Array(0, 1, 2), Array(), Array(), 0, LeaderRecoveryState.RECOVERED, 0, -1),
      1 -> new PartitionRegistration(Array(1, 2, 3), Array(1, 2, 3), Array(), Array(), 1, LeaderRecoveryState.RECOVERED, 0, -1)
    ).map { case (k, v) => Integer.valueOf(k) -> v }.asJava
    val topicId = Uuid.randomUuid()
    migrationState = migrationClient.topicClient().createTopic("test", topicId, partitions, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    migrationState = migrationClient.topicClient().createTopic("test", topicId, partitions, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())
  }

  @Test
  def testClaimAbsentController(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())
    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(1, migrationState.zkControllerEpochZkVersion())
  }

  @Test
  def testExistingKRaftControllerClaim(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())
    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(1, migrationState.zkControllerEpochZkVersion())

    // We don't require a KRaft controller to release the controller in ZK before another KRaft controller
    // can claim it. This is because KRaft leadership comes from Raft and we are just synchronizing it to ZK.
    var otherNodeState = ZkMigrationLeadershipState.EMPTY
      .withNewKRaftController(3001, 43)
      .withKRaftMetadataOffsetAndEpoch(100, 42);
    otherNodeState = migrationClient.claimControllerLeadership(otherNodeState)
    assertEquals(2, otherNodeState.zkControllerEpochZkVersion())
    assertEquals(3001, otherNodeState.kraftControllerId())
    assertEquals(43, otherNodeState.kraftControllerEpoch())
  }

  @Test
  def testNonIncreasingKRaftEpoch(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())

    migrationState = migrationState.withNewKRaftController(3001, InitialControllerEpoch)
    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(1, migrationState.zkControllerEpochZkVersion())

    migrationState = migrationState.withNewKRaftController(3001, InitialControllerEpoch - 1)
    val t1 = assertThrows(classOf[ControllerMovedException], () => migrationClient.claimControllerLeadership(migrationState))
    assertEquals("Cannot register KRaft controller 3001 with epoch 41 as the current controller register in ZK has the same or newer epoch 42.", t1.getMessage)

    migrationState = migrationState.withNewKRaftController(3001, InitialControllerEpoch)
    val t2 = assertThrows(classOf[ControllerMovedException], () => migrationClient.claimControllerLeadership(migrationState))
    assertEquals("Cannot register KRaft controller 3001 with epoch 42 as the current controller register in ZK has the same or newer epoch 42.", t2.getMessage)

    migrationState = migrationState.withNewKRaftController(3001, 100)
    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(migrationState.kraftControllerEpoch(), 100)
    assertEquals(migrationState.kraftControllerId(), 3001)
  }

  @Test
  def testClaimAndReleaseExistingController(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())

    val (epoch, zkVersion) = zkClient.registerControllerAndIncrementControllerEpoch(100)
    assertEquals(epoch, 2)
    assertEquals(zkVersion, 1)

    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(2, migrationState.zkControllerEpochZkVersion())
    zkClient.getControllerEpoch match {
      case Some((zkEpoch, stat)) =>
        assertEquals(3, zkEpoch)
        assertEquals(2, stat.getVersion)
      case None => fail()
    }
    assertEquals(3000, zkClient.getControllerId.get)
    assertThrows(classOf[ControllerMovedException], () => zkClient.registerControllerAndIncrementControllerEpoch(100))

    migrationState = migrationClient.releaseControllerLeadership(migrationState)
    val (epoch1, zkVersion1) = zkClient.registerControllerAndIncrementControllerEpoch(100)
    assertEquals(epoch1, 4)
    assertEquals(zkVersion1, 3)
  }

  @Test
  def testReadAndWriteProducerId(): Unit = {
    def generateNextProducerIdWithZkAndRead(): Long = {
      // Generate a producer ID in ZK
      val manager = ProducerIdManager.zk(1, zkClient)
      manager.generateProducerId()

      val records = new java.util.ArrayList[java.util.List[ApiMessageAndVersion]]()
      migrationClient.migrateProducerId(batch => records.add(batch))
      assertEquals(1, records.size())
      assertEquals(1, records.get(0).size())

      val record = records.get(0).get(0).message().asInstanceOf[ProducerIdsRecord]
      record.nextProducerId()
    }

    // Initialize with ZK ProducerIdManager
    assertEquals(0, generateNextProducerIdWithZkAndRead())

    // Update next producer ID via migration client
    migrationState = migrationClient.writeProducerId(6000, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    // Switch back to ZK, it should provision the next block
    assertEquals(7000, generateNextProducerIdWithZkAndRead())
  }

  @Test
  def testMigrateTopicConfigs(): Unit = {
    val props = new Properties()
    props.put(TopicConfig.FLUSH_MS_CONFIG, "60000")
    props.put(TopicConfig.RETENTION_MS_CONFIG, "300000")
    adminZkClient.createTopicWithAssignment("test", props, Map(0 -> Seq(0, 1, 2), 1 -> Seq(1, 2, 0), 2 -> Seq(2, 0, 1)), usesTopicId = true)

    val brokers = new java.util.ArrayList[Integer]()
    val batches = new java.util.ArrayList[java.util.List[ApiMessageAndVersion]]()
    migrationClient.migrateTopics(batch => batches.add(batch), brokerId => brokers.add(brokerId))
    assertEquals(1, batches.size())
    val configs = batches.get(0)
      .asScala
      .map {_.message() }
      .filter(message => MetadataRecordType.fromId(message.apiKey()).equals(MetadataRecordType.CONFIG_RECORD))
      .map { _.asInstanceOf[ConfigRecord] }
      .map { record => record.name() -> record.value()}
      .toMap
    assertEquals(2, configs.size)
    assertTrue(configs.contains(TopicConfig.FLUSH_MS_CONFIG))
    assertEquals("60000", configs(TopicConfig.FLUSH_MS_CONFIG))
    assertTrue(configs.contains(TopicConfig.RETENTION_MS_CONFIG))
    assertEquals("300000", configs(TopicConfig.RETENTION_MS_CONFIG))
  }

  @Test
  def testTopicAndBrokerConfigsMigrationWithSnapshots(): Unit = {
    val kraftWriter = new KRaftMigrationZkWriter(migrationClient, (_, operation) => {
      migrationState = operation.apply(migrationState)
    })

    // Add add some topics and broker configs and create new image.
    val topicName = "testTopic"
    val partition = 0
    val tp = new TopicPartition(topicName, partition)
    val leaderPartition = 1
    val leaderEpoch = 100
    val partitionEpoch = 10
    val brokerId = "1"
    val replicas = List(1, 2, 3).map(int2Integer).asJava
    val topicId = Uuid.randomUuid()
    val props = new Properties()
    props.put(KafkaConfig.DefaultReplicationFactorProp, "1") // normal config
    props.put(KafkaConfig.SslKeystorePasswordProp, SECRET) // sensitive config

    //    // Leave Zk in an incomplete state.
    //    zkClient.createTopicAssignment(topicName, Some(topicId), Map(tp -> Seq(1)))

    val delta = new MetadataDelta(MetadataImage.EMPTY)
    delta.replay(new TopicRecord()
      .setTopicId(topicId)
      .setName(topicName)
    )
    delta.replay(new PartitionRecord()
      .setTopicId(topicId)
      .setIsr(replicas)
      .setLeader(leaderPartition)
      .setReplicas(replicas)
      .setAddingReplicas(List.empty.asJava)
      .setRemovingReplicas(List.empty.asJava)
      .setLeaderEpoch(leaderEpoch)
      .setPartitionEpoch(partitionEpoch)
      .setPartitionId(partition)
      .setLeaderRecoveryState(LeaderRecoveryState.RECOVERED.value())
    )
    // Use same props for the broker and topic.
    props.asScala.foreach { case (key, value) =>
      delta.replay(new ConfigRecord()
        .setName(key)
        .setValue(value)
        .setResourceName(topicName)
        .setResourceType(ConfigResource.Type.TOPIC.id())
      )
      delta.replay(new ConfigRecord()
        .setName(key)
        .setValue(value)
        .setResourceName(brokerId)
        .setResourceType(ConfigResource.Type.BROKER.id())
      )
    }
    val image = delta.apply(MetadataProvenance.EMPTY)

    // Handle migration using the generated snapshot.
    kraftWriter.handleLoadSnapshot(image)

    // Verify topic state.
    val topicIdReplicaAssignment =
      zkClient.getReplicaAssignmentAndTopicIdForTopics(Set(topicName))
    assertEquals(1, topicIdReplicaAssignment.size)
    topicIdReplicaAssignment.foreach { assignment =>
      assertEquals(topicName, assignment.topic)
      assertEquals(Some(topicId), assignment.topicId)
      assertEquals(Map(tp -> ReplicaAssignment(replicas.asScala.map(Integer2int).toSeq)),
        assignment.assignment)
    }

    // Verify the topic partition states.
    val topicPartitionState = zkClient.getTopicPartitionState(tp)
    assertTrue(topicPartitionState.isDefined)
    topicPartitionState.foreach { state =>
      assertEquals(leaderPartition, state.leaderAndIsr.leader)
      assertEquals(leaderEpoch, state.leaderAndIsr.leaderEpoch)
      assertEquals(LeaderRecoveryState.RECOVERED, state.leaderAndIsr.leaderRecoveryState)
      assertEquals(replicas.asScala.map(Integer2int).toList, state.leaderAndIsr.isr)
    }

    // Verify the broker and topic configs (including sensitive configs).
    val brokerProps = zkClient.getEntityConfigs(ConfigType.Broker, brokerId)
    val topicProps = zkClient.getEntityConfigs(ConfigType.Topic, topicName)
    assertEquals(2, brokerProps.size())

    brokerProps.asScala.foreach { case (key, value) =>
      if (key == KafkaConfig.SslKeystorePasswordProp) {
        assertEquals(SECRET, encoder.decode(value).value)
      } else {
        assertEquals(props.getProperty(key), value)
      }
    }

    topicProps.asScala.foreach { case (key, value) =>
      if (key == KafkaConfig.SslKeystorePasswordProp) {
        assertEquals(SECRET, encoder.decode(value).value)
      } else {
        assertEquals(props.getProperty(key), value)
      }
    }
  }
}
