package twitter;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import com.twitter.clientlib.ApiException;
import com.twitter.clientlib.TwitterCredentialsBearer;
import com.twitter.clientlib.api.TwitterApi;
import com.twitter.clientlib.model.StreamingTweetResponse;

public class TwitterStreaming {

	public static void main(String[] args) {

		TwitterApi apiInstance = new TwitterApi(new TwitterCredentialsBearer(
				"AAAAAAAAAAAAAAAAAAAAAAbrhAEAAAAAu6RSyocHwha1%2BRQXdj2HnJcp934%3DXPQc81MtdV6zniiQqNc9HcJJJD0M9ubwdDnOkJhX8QBnrCBqzY"));

		Set<String> tweetFields = new HashSet<>();
		tweetFields.add("author_id");
		tweetFields.add("id");
		tweetFields.add("created_at");

		try {
			InputStream streamResult = apiInstance.tweets().sampleStream().backfillMinutes(0).tweetFields(tweetFields)
					.execute();
			Responder responder = new Responder();
			TweetsStreamListenersExecutor tsle = new TweetsStreamListenersExecutor(streamResult);
			tsle.addListener(responder);
			tsle.executeListeners();

		} catch (ApiException e) {
			System.err.println("Status code: " + e.getCode());
			System.err.println("Reason: " + e.getResponseBody());
			System.err.println("Response headers: " + e.getResponseHeaders());
			e.printStackTrace();
		}
	}
}

class Responder implements TweetsStreamListener {
	@Override
	public void actionOnTweetsStream(StreamingTweetResponse streamingTweet) {
		if (streamingTweet == null) {
			System.err.println("Error: actionOnTweetsStream - streamingTweet is null ");
			return;
		}

		if (streamingTweet.getErrors() != null) {
			streamingTweet.getErrors().forEach(System.out::println);
		} else if (streamingTweet.getData() != null) {
			System.out.println("New streaming tweet: " + streamingTweet.getData().getText());

			Properties props = new Properties();
			props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
			props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
			props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

			Producer<String, String> producer = new KafkaProducer<>(props);
			ProducerRecord<String, String> record = new ProducerRecord<>("messages", streamingTweet.getData().getId(),
					streamingTweet.getData().getText());
			try {
				RecordMetadata metadata = producer.send(record).get();
			} catch (InterruptedException | ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				producer.flush();
				producer.close();
			}
		}
	}
}
