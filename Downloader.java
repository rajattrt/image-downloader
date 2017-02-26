import hipi.image.ImageHeader.ImageType;
import hipi.imagebundle.HipiImageBundle;
import hipi.image.ImageHeader;
import hipi.image.io.JPEGImageUtil;
import hipi.image.io.PNGImageUtil;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Iterator;


public class Downloader extends Configured implements Tool {

  public static class DownloaderMapper extends Mapper<IntWritable, Text, BooleanWritable, Text> {

    private static Configuration conf;

    @Override
    public void setup(Context context) throws IOException, InterruptedException {
      this.conf = context.getConfiguration();
    }

    // Download images at the list of input URLs and store them in a temporary HIB.
    @Override
    public void map(IntWritable key, Text value, Context context) throws IOException, InterruptedException {

      // Create path for temporary HIB file
      String tempPath = conf.get("downloader.outpath") + key.get() + ".hib.tmp";
      HipiImageBundle hib = new HipiImageBundle(new Path(tempPath), conf);
      hib.open(HipiImageBundle.FILE_MODE_WRITE, true);

      // The value argument contains a list of image URLs delimited by \n. Setup buffered reader to allow processing this string line by line.
      BufferedReader reader = new BufferedReader(new StringReader(value.toString()));
      String uri;
      int i = key.get();
      int iprev = i;

      // Iterate through URLs
      while ((uri = reader.readLine()) != null) {

	// Put at most 100 images in a temporary HIB
        if (i >= iprev + 100) {
          hib.close();
          context.write(new BooleanWritable(true), new Text(hib.getPath().toString()));
          tempPath = conf.get("downloader.outpath") + i + ".hib.tmp";
          hib = new HipiImageBundle(new Path(tempPath), conf);
          hib.open(HipiImageBundle.FILE_MODE_WRITE, true);
          iprev = i;
        }

	// Setup to time download
        long startT = 0;
        long stopT = 0;
        startT = System.currentTimeMillis();

        // Perform download and update HIB
        try {

          String type = "";
          URLConnection conn;

	  // Attempt to download image at URL using java.net
          try {
            URL link = new URL(uri);
            System.err.println("Downloading " + link.toString());
            conn = link.openConnection();
            conn.connect();
            type = conn.getContentType();
          } catch (Exception e) {
            System.err.println("Connection error while trying to download: " + uri);
            continue;
          }

	  // Check that image format is supported, header is parsable, and add to HIB if so
          if (type != null && (type.compareTo("image/jpeg") == 0 || type.compareTo("image/png") == 0)) {

	    // Get input stream for URL connection
	    InputStream bis = new BufferedInputStream(conn.getInputStream());

	    // Mark current location in stream for later reset
	    bis.mark(Integer.MAX_VALUE);

	    // Attempt to decode the image header
	    ImageHeader header = (type.compareTo("image/jpeg") == 0 ? JPEGImageUtil.getInstance().decodeImageHeader(bis) : PNGImageUtil.getInstance().decodeImageHeader(bis));

	    if (header == null)  {
	      System.err.println("Failed to parse header, not added to HIB: " + uri);
	    } else {

	      // Passed header decode test, so reset to beginning of stream
	      bis.reset();

	      // Add image to HIB
	      hib.addImage(bis, type.compareTo("image/jpeg") == 0 ? ImageType.JPEG_IMAGE : ImageType.PNG_IMAGE);

	      System.err.println("Added to HIB: " + uri);

	    }
          } else {
	    System.err.println("Unrecognized HTTP content type or unsupported image format [" + type + "], not added to HIB: " + uri);
	  }

        } catch (Exception e) {
          e.printStackTrace();
          System.err.println("Encountered network error while trying to download: " + uri);
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ie) {
            ie.printStackTrace();
          }
        }

        i++;

        // Report success and elapsed time
        stopT = System.currentTimeMillis();
        float el = (float) (stopT - startT) / 1000.0f;
        System.err.println("> Time elapsed " + el + " seconds");
      }

      try {

	// Output key/value pair to reduce layer consisting of boolean and path to HIB
        context.write(new BooleanWritable(true), new Text(hib.getPath().toString()));

	// Cleanup
        reader.close();
        hib.close();

      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    
  }

  public static class DownloaderReducer extends
      Reducer<BooleanWritable, Text, BooleanWritable, Text> {

    private static Configuration conf;

    @Override
    public void setup(Context context) throws IOException, InterruptedException {
      this.conf = context.getConfiguration();
    }

    // Combine HIBs produced by the map tasks into a single HIB
    @Override
    public void reduce(BooleanWritable key, Iterable<Text> values, Context context)
      throws IOException, InterruptedException {
      
      if (key.get()) {

	// Get path to output HIB
        FileSystem fileSystem = FileSystem.get(conf);
        Path outputHibPath = new Path(conf.get("downloader.outfile"));

	// Create HIB for writing
        HipiImageBundle hib = new HipiImageBundle(outputHibPath, conf);
        hib.open(HipiImageBundle.FILE_MODE_WRITE, true);

	// Iterate over the temporary HIB files created by map tasks
        for (Text tempString : values) {

	  // Open the temporary HIB file
          Path tempPath = new Path(tempString.toString());
          HipiImageBundle inputBundle = new HipiImageBundle(tempPath, conf);

	  // Append temporary HIB file to output HIB (this is fast)
          hib.append(inputBundle);

	  // Remove temporary HIB (both .hib and .hib.dat files)
          Path indexPath = inputBundle.getPath();
          Path dataPath = new Path(indexPath.toString() + ".dat");
          fileSystem.delete(indexPath, false);
          fileSystem.delete(dataPath, false);

	  // Emit output key/value pair indicating temporary HIB has been processed
          Text outputPath = new Text(inputBundle.getPath().toString());
          context.write(new BooleanWritable(true), outputPath);
          context.progress();
        }

	// Finalize output HIB
        hib.close();
      }
    }
  }


  public int run(String[] args) throws Exception {

    if (args.length != 3) {
      System.out.println("Usage: downloader <input text file with list of URLs> <output HIB> <number of download nodes>");
      System.exit(0);
    }

    String inputFile = args[0];
    String outputFile = args[1];
    String outputPath = outputFile.substring(0, outputFile.lastIndexOf('/') + 1);
    int nodes = Integer.parseInt(args[2]);
    
    Configuration conf = new Configuration();
    
    //Attaching constant values to Configuration
    conf.setInt("downloader.nodes", nodes);
    conf.setStrings("downloader.outfile", outputFile);
    conf.setStrings("downloader.outpath", outputPath);

    Job job = Job.getInstance(conf, "Downloader");
    job.setJarByClass(Downloader.class);
    job.setMapperClass(DownloaderMapper.class);
    job.setReducerClass(DownloaderReducer.class);
    job.setInputFormatClass(DownloaderInputFormat.class);
    job.setOutputKeyClass(BooleanWritable.class);
    job.setOutputValueClass(Text.class);
    job.setNumReduceTasks(1);

    FileOutputFormat.setOutputPath(job, new Path(outputFile + "_output"));

    DownloaderInputFormat.setInputPaths(job, new Path(inputFile));

    return job.waitForCompletion(true) ? 0 : 1;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Downloader(), args);
    System.exit(res);
  }
}
