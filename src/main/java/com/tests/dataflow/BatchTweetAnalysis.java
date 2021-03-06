package com.tests.dataflow;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.io.BigQueryIO;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.options.DataflowPipelineOptions;
import com.google.cloud.dataflow.sdk.options.Validation;
import com.google.cloud.dataflow.sdk.options.Description;
import com.google.cloud.dataflow.sdk.options.Default;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.Count;
import com.google.cloud.dataflow.sdk.transforms.Filter;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.transforms.Top;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.transforms.windowing.SlidingWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.FixedWindows;

import com.google.api.services.bigquery.model.TableRow;

import org.json.JSONObject;
import org.json.JSONArray;

import org.joda.time.Duration;
import org.joda.time.Instant;

import java.util.List;

public class BatchTweetAnalysis {

	// Class to transform the Tweets into Entities. One Tweet contains more 
	// than one entity
	// The timestamp of the entity is the one of the original Tweet
	static class ExtractEntities extends DoFn<TableRow, String>{
		@Override
		public void processElement(ProcessContext c){
			String in = (String)c.element().get("json");
			JSONObject tweet = new JSONObject(in);
			Instant timestamp = new Instant(tweet.getLong("timestamp_ms"));
			JSONObject entities = tweet.getJSONObject("entities");
			JSONArray mentions = entities.getJSONArray("user_mentions");
			for (int i = 0; i < mentions.length(); i++){
				JSONObject mention = mentions.getJSONObject(i);
				c.outputWithTimestamp("@"+mention.getString("screen_name").toLowerCase(),timestamp);
			}
			JSONArray hashtags = entities.getJSONArray("hashtags");
			for (int i = 0; i < hashtags.length(); i++){
				JSONObject hashtag = hashtags.getJSONObject(i);
				c.outputWithTimestamp("#"+hashtag.getString("text").toLowerCase(),timestamp);
			}
		}
	}

	// Code for splitting entities in mention/hashtag
	// It take a KeyValue element (Entity,Count) and transform it in a KeyValue of KeyValue
	// (Type,(Entity, Count))
	static class SplitMentionHash extends DoFn<KV<String,Long>,KV<String,KV<String,Long>>>{
		@Override
		public void processElement(ProcessContext c){
			if (c.element().getKey().startsWith("@"))
				c.output(KV.<String,KV<String,Long>>of("mention",c.element()));
			else
				c.output(KV.<String,KV<String,Long>>of("hashtag",c.element()));
		}
	}

	// Output code. It convert the result of top into a string in csv format:
	// - entity type (hashtag, mention)
	// - timestamp (the beginning of the window)
	// - list of couple entity,count
	static class TransformForPrint extends DoFn<KV<String,List<KV<String,Long>>>, String>{
		@Override
		public void processElement(ProcessContext c){
			String output = "" + c.element().getKey() + "," + c.timestamp();
			for (KV<String, Long> e:c.element().getValue()){
				output += "," + e.getKey() + ","  + e.getValue();
			}
			c.output(output);
		}
	}

	// Code for filtering tweets.
	// It accepts the TableRows because it is applied before the entity extraction
	static class TweetFilter implements SerializableFunction<TableRow,Boolean>{
		private String[] contains;
		private String[] not_contains;
		private Boolean rt;
		private Instant start;
		private Instant stop;

		// - contains: Comma separated list of string to search in the twitter 
		//             text for the tweet to be included
		// - not_contains: Comma separated list of string to search in the twitter 
		//                 text for the tweet to be excluded
		// - rt: Include retweets
		// - start: Start time for the tweet to be included
		// - stop: Stop time for the tweet to be included
		public TweetFilter(String contains, String not_contains, Boolean rt, String start, String stop){
			this.rt = rt;

			if (contains != null){
				String[] tmp_contains = contains.split(",");
				this.contains = new String[tmp_contains.length];
				for (int c = 0; c < tmp_contains.length; c++)
					this.contains[c] = tmp_contains[c].toLowerCase();
			}else{
				this.contains = new String[0];
			}

			if (not_contains != null){
				String[] tmp_contains = not_contains.split(",");
				this.not_contains = new String[tmp_contains.length];
				for (int c = 0; c < tmp_contains.length; c++)
					this.not_contains[c] = tmp_contains[c].toLowerCase();
			}else{
				this.not_contains = new String[0];
			}

			if(start != null){
				this.start = Instant.parse(start);
			}
			else{
				this.start = null;
			}

			if(stop != null){
				this.stop = Instant.parse(stop);
			}
			else{
				this.stop = null;
			}
		}

		public Boolean apply(TableRow input){
			JSONObject tweet = new JSONObject(input.get("json").toString());
			if (!this.rt && (boolean)input.get("is_retweet"))
				return false;

			if (this.start != null || this.stop != null){
				Instant timestamp = new Instant(tweet.getLong("timestamp_ms"));
				if (this.start != null && timestamp.compareTo(this.start) < 0)
					return false;
				if (this.stop != null && timestamp.compareTo(this.stop) > 0)
					return false;
			}

			boolean ok_con, ok_nocon;
			if (this.contains.length>0){
				String text = tweet.getString("text").toLowerCase();
				ok_con = false;
				for (String s: this.contains){
					if (text.contains(s)){
						ok_con = true;
						break;
					}
				}
			}else{
				ok_con = true;
			}

			if (this.not_contains.length>0){
				String text = tweet.getString("text").toLowerCase();
				ok_nocon = true;
				for (String s: this.not_contains){
					if (text.contains(s)){
						ok_nocon = false;
						break;
					}
				}
			}else{
				ok_nocon = true;
			}

			return ok_con && ok_nocon;
		}

	}

	// Filter to exclude/include only entity in the list
	static class FilterIn implements SerializableFunction<String,Boolean>{
		private String[] list;
		private Boolean in;

		// - list: String, comma separated list of String to filter
		// - in: Control exclusion/inclusion. If False filter out the entity in the list,
		//       If True filter in
		public FilterIn(String list, Boolean in){
			if (list == null){
				this.list = null;
			}else{
				this.list = list.split(",");
				this.in = in;
			}
		}

		public FilterIn(String list){
			if (list == null){
				this.list = null;
			}else{
				this.list = list.split(",");
				this.in = true;
			}
		}

		public Boolean apply(String input){
			if (this.list == null)
				return true;
			for (String e: this.list) {
				if (input.equalsIgnoreCase(e))
					return in;
			}
			return !in;
		}

	}

	// Command line options
	private interface BatchTweetAnalysisOptions extends PipelineOptions {
		@Description("Window size in minutes (default 30)")
		@Default.Integer(30)
		int getWindowSize();
		void setWindowSize(int value);

		@Description("Window frequency in minutes (default 5)")
		@Default.Integer(5)
		int getWindowFreq();
		void setWindowFreq(int value);

		@Description("File to save to. The format should be gs://<bucket>/<path>")
		@Validation.Required
		String getSaveTo();
		void setSaveTo(String value);

		@Description("Big query table to read from. The format should be <project>:<db>.<table>")
		@Validation.Required
		String getReadFrom();
		void setReadFrom(String value);

		@Description("Accept retweet")
		@Default.Boolean(true)
		boolean getRetweet();
		void setRetweet(boolean value);

		@Description("Tweet should Contains")
		String getContains();
		void setContains(String value);

		@Description("Tweet should Not Contains")
		String getNotContains();
		void setNotContains(String value);

		@Description("Tweet should be received after")
		String getStart();
		void setStart(String value);

		@Description("Tweet should be received after")
		String getStop();
		void setStop(String value);

		@Description("Entities to filter")
		String getFilterEntities();
		void setFilterEntities(String value);
	}


	public static void main(String[] args) {
		BatchTweetAnalysisOptions options = PipelineOptionsFactory.fromArgs(args)
			.withValidation()
			.as(BatchTweetAnalysisOptions.class);
		DataflowPipelineOptions dataflowOptions = options.as(DataflowPipelineOptions.class);

		dataflowOptions.setNumWorkers(1);

		// Create the Pipeline with default options.
		Pipeline pipeline = Pipeline.create(options);

		pipeline
			.apply(BigQueryIO.Read.from(options.getReadFrom()))
			.apply(Filter.by(new TweetFilter(
				options.getContains(),
				options.getNotContains(),
				options.getRetweet(),
				options.getStart(),
				options.getStop()
				)))
			.apply(ParDo.of(new ExtractEntities()))
			// Group in Sliding windows
			.apply(Filter.by(new FilterIn(options.getFilterEntities(),false)))
			.apply(Window.<String>into(
				SlidingWindows.of(Duration.standardMinutes(options.getWindowSize()))
				.every(Duration.standardMinutes(options.getWindowFreq()))))
			// The count group the strings and count them. It return a KeyValue with Entity, Count
			.apply(Count.<String>perElement())
			// Split the mentions
			.apply(ParDo.of(new SplitMentionHash()))
			// To the top count for each group has grouped by the Key
			// The comparison function order by the Value
			// It returns a Key Value element containing the Type as key and a list of key value
			// with the top content
			.apply(
				Top.<String,KV<String,Long>,KV.OrderByValue<String,Long>>perKey(
					10,new KV.OrderByValue<String,Long>()))
			.apply(ParDo.of(new TransformForPrint()))
			.apply(TextIO.Write.to(options.getSaveTo())
				.withoutSharding());

		pipeline.run();
	}
}
