package edu.miu.cs523.project;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaPairInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;

import com.google.common.base.Optional;

import kafka.serializer.StringDecoder;
import scala.Tuple2;

public class SparkKafkaConsumer {

	public static void main(String[] args) throws IOException {

		final String HBASE_TABLE = "hashtags";
		final String KAFKA_BROKER = "localhost:9092";
		final String KAFKA_TOPIC = "messages";
		
		System.out.println("Spark Streaming started now .....");

		SparkConf conf = new SparkConf().setAppName("kafka-sandbox").setMaster("local[*]");
		JavaSparkContext sc = new JavaSparkContext(conf);

		JavaStreamingContext ssc = new JavaStreamingContext(sc, new Duration(20000));
		ssc.checkpoint("checkpoint");

		Map<String, String> kafkaParams = new HashMap<>();
		kafkaParams.put("metadata.broker.list", KAFKA_BROKER);
		Set<String> topics = Collections.singleton(KAFKA_TOPIC);

		JavaPairInputDStream<String, String> directKafkaStream = KafkaUtils.createDirectStream(ssc, String.class,
				String.class, StringDecoder.class, StringDecoder.class, kafkaParams, topics);

		JavaDStream<String> lines = directKafkaStream.map(r -> r._2());
		JavaDStream<String> words = lines.flatMap(x -> Arrays.asList(Pattern.compile(" ").split(x)));
		JavaPairDStream<String, Integer> hashtags = words.filter(x -> x.startsWith("#"))
				.mapToPair(s -> new Tuple2<>(s, 1)).reduceByKey((i1, i2) -> i1 + i2);
		hashtags.print();

		Function2<List<Integer>, Optional<Integer>, Optional<Integer>> UPDATE_FUNCTION = new Function2<List<Integer>, Optional<Integer>, Optional<Integer>>() {
			@Override
			public Optional<Integer> call(List<Integer> values, Optional<Integer> state) {
				Integer newSum = state != Optional.<Integer>absent() ? state.get() : 0;
				Iterator<Integer> i = values.iterator();
				while (i.hasNext()) {
					newSum += i.next();
				}
				return Optional.of(newSum);
			}
		};

		JavaPairDStream<String, Integer> hashtagCounts = hashtags.updateStateByKey(UPDATE_FUNCTION);

		hashtagCounts.print();

		hashtagCounts.foreachRDD(new VoidFunction<JavaPairRDD<String, Integer>>() {
			private static final long serialVersionUID = 1L;

			@Override
			public void call(JavaPairRDD<String, Integer> rdd) throws Exception {
				Configuration hbaseConf = HBaseConfiguration.create();
				hbaseConf.set("hbase.zookeeper.quorum", "hbase-docker");
				hbaseConf.set("hbase.zookeeper.property.clientPort", "2181");
				hbaseConf.set("hbase.cluster.distributed", "false");
				hbaseConf.set("hbase.zookeeper.property.maxClientCnxns", "1000");

				Connection connection = ConnectionFactory.createConnection(hbaseConf);
				Admin admin = connection.getAdmin();
				TableName tn = TableName.valueOf(HBASE_TABLE);

				if (admin.tableExists(tn)) {
					System.out.println("Table already exists");
				} else {
					System.out.println("Create table ...");
					HTableDescriptor hbaseTable = new HTableDescriptor(TableName.valueOf(HBASE_TABLE));
					hbaseTable.addFamily(new HColumnDescriptor("info"));
					admin.createTable(hbaseTable);
				}
				HTable hTable = new HTable(hbaseConf, tn);
				if (rdd.count() > 0) {
					rdd.collect().forEach(rawRecord -> {
						Put put = new Put(Bytes.toBytes(rawRecord._1));
						put.addColumn(Bytes.toBytes("info"), Bytes.toBytes("count"), Bytes.toBytes(rawRecord._2));
						try {
							hTable.put(put);
						} catch (IOException e) {
							e.printStackTrace();
						}
					});
				}
			}
		});
		ssc.start();
		ssc.awaitTermination();
	}
}