package com.spredfast.kafka.test;

import static com.google.common.base.Preconditions.checkState;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.apache.kafka.common.utils.SystemTime;
import org.apache.kafka.connect.runtime.Connect;
import org.apache.kafka.connect.runtime.Herder;
import org.apache.kafka.connect.runtime.Worker;
import org.apache.kafka.connect.runtime.WorkerConfig;
import org.apache.kafka.connect.runtime.rest.RestServer;
import org.apache.kafka.connect.runtime.standalone.StandaloneConfig;
import org.apache.kafka.connect.runtime.standalone.StandaloneHerder;
import org.apache.kafka.connect.storage.FileOffsetBackingStore;
import com.google.common.base.Functions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.netflix.curator.test.InstanceSpec;
import com.netflix.curator.test.TestingServer;

import kafka.admin.AdminUtils;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import kafka.utils.SystemTime$;
import scala.Option;

public class KafkaIntegrationTests {

	private static final int SLEEP_INTERVAL = 300;

	public static Kafka givenLocalKafka() throws Exception {
		return new Kafka();
	}

	public static void givenLocalKafka(int kafkaPort, IntConsumer localPort) throws Exception {
		try (Kafka kafka = givenLocalKafka()) {
			localPort.accept(kafka.localPort());
		}
	}

	public static void givenKafkaConnect(int kafkaPort, Consumer<Herder> consumer) throws Exception {
		try (KafkaConnect connect = givenKafkaConnect(kafkaPort)) {
			consumer.accept(connect.herder());
		}
	}

	public static void waitForPassing(Duration timeout, Runnable test) {
		waitForPassing(timeout, () -> {
			test.run();
			return null;
		});
	}

	public static void waitForPassing(Duration timeout, Callable<?> test) {
		AssertionError last = null;
		for (int i = 0; i < timeout.toMillis() / SLEEP_INTERVAL; i++) {
			try {
				test.call();
				return;
			} catch (AssertionError e) {
				last = e;
				try {
					Thread.sleep(SLEEP_INTERVAL);
				} catch (InterruptedException e1) {
					Throwables.propagate(e1);
				}
			} catch (Exception e) {
				Throwables.propagate(e);
			}
		}
		if (last != null) {
			throw last;
		}
	}

	public static KafkaConnect givenKafkaConnect(int kafkaPort) throws IOException {
		File tempFile = File.createTempFile("connect", "offsets");
		WorkerConfig config = new StandaloneConfig(ImmutableMap.<String, String>builder()
			.put("bootstrap.servers", "localhost:" + kafkaPort)
			// perform no conversion
			.put("key.converter", "com.spredfast.kafka.connect.s3.AlreadyBytesConverter")
			.put("value.converter", "com.spredfast.kafka.connect.s3.AlreadyBytesConverter")
			.put("internal.key.converter", "org.apache.kafka.connect.json.JsonConverter")
			.put("internal.value.converter", "org.apache.kafka.connect.json.JsonConverter")
			.put("internal.key.converter.schemas.enable", "true")
			.put("internal.value.converter.schemas.enable", "true")
			.put("offset.storage.file.filename", tempFile.getCanonicalPath())
			.put("offset.flush.interval.ms", "1000")
			.put("consumer.metadata.max.age.ms", "1000")
			.put("rest.port", "" + InstanceSpec.getRandomPort())
			.build()
		);

		Worker worker = new Worker("1", new SystemTime(), config, new FileOffsetBackingStore());
		Herder herder = new StandaloneHerder(worker);
		RestServer restServer = new RestServer(config);
		Connect connect = new Connect(herder, restServer);
		connect.start();
		return new KafkaConnect(connect, herder);
	}

	public static class KafkaConnect implements AutoCloseable {

		private final Connect connect;
		private final Herder herder;

		public KafkaConnect(Connect connect, Herder herder) {
			this.connect = connect;
			this.herder = herder;
		}

		@Override
		public void close() throws Exception {
			connect.stop();
			connect.awaitStop();
		}

		public Herder herder() {
			return herder;
		}
	}

	public static class Kafka implements AutoCloseable {
		private final TestingServer zk;
		private final KafkaServer kafkaServer;

		public Kafka() throws Exception {
			zk = new TestingServer();
			File tmpDir = Files.createTempDir();
			KafkaConfig config = new KafkaConfig(Maps.transformValues(ImmutableMap.<String, Object>builder()
				.put("port", InstanceSpec.getRandomPort())
				.put("broker.id", "1")
				.put("offsets.topic.replication.factor", 1)
				.put("log.dir", tmpDir.getCanonicalPath())
				.put("zookeeper.connect", zk.getConnectString())
				.build(), Functions.toStringFunction()));
			kafkaServer = new KafkaServer(config, SystemTime$.MODULE$, Option.empty());
			kafkaServer.startup();
		}

		public int localPort() {
			return kafkaServer.config().advertisedPort();
		}

		@Override
		public void close() throws Exception {
			kafkaServer.shutdown();
			kafkaServer.awaitShutdown();
			zk.close();
		}

		public String createUniqueTopic(String prefix) throws InterruptedException {
			return createUniqueTopic(prefix, 1);
		}

		public String createUniqueTopic(String prefix, int partitions) throws InterruptedException {
			checkReady();
			String topic = (prefix + UUID.randomUUID()).replaceAll("[^a-zA-Z0-9._-]", "_");
			AdminUtils.createTopic(kafkaServer.zkUtils(), topic, partitions, 1, new Properties(), AdminUtils.createTopic$default$6());
			waitForPassing(Duration.ofSeconds(5), () -> {
				assertTrue(AdminUtils.fetchTopicMetadataFromZk(topic, kafkaServer.zkUtils())
					.partitionMetadata().stream()
					.allMatch(pm -> !pm.leader().isEmpty()));
			});
			return topic;
		}

		public void checkReady() throws InterruptedException {
			checkReady(Duration.ofSeconds(15));
		}

		public void checkReady(Duration timeout) throws InterruptedException {
			waitForPassing(timeout, () -> assertNotNull(kafkaServer.kafkaHealthcheck()));
		}
	}

}
