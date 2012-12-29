package edu.umn.cs;

import java.io.IOException;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Text2;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.spatial.GridInfo;
import org.apache.hadoop.spatial.Point;
import org.apache.hadoop.spatial.Rectangle;
import org.apache.hadoop.spatial.Shape;

import edu.umn.cs.spatialHadoop.TigerShape;
import edu.umn.cs.spatialHadoop.operations.Sampler;

public class CommandLineArguments {
  private static final Log LOG = LogFactory.getLog(CommandLineArguments.class);
  
  private String[] args;

  public CommandLineArguments(String[] args) {
    this.args = args;
  }
  
  public Rectangle getRectangle() {
    Rectangle rect = null;
    for (String arg : args) {
      if (arg.startsWith("rect:") || arg.startsWith("rectangle:")) {
        rect = new Rectangle();
        rect.fromText(new Text(arg.substring(arg.indexOf(':')+1)));
      }
    }
    return rect;
  }
  
  public Path[] getPaths() {
    Vector<Path> inputPaths = new Vector<Path>();
    for (String arg : args) {
      if (arg.startsWith("-") && arg.length() > 1) {
        // Skip
      } else if (arg.indexOf(':') != -1 && arg.indexOf(":/") == -1) {
        // Skip
      } else {
        inputPaths.add(new Path(arg));
      }
    }
    return inputPaths.toArray(new Path[inputPaths.size()]);
  }
  
  public Path getPath() {
    return getPaths()[0];
  }
  
  public GridInfo getGridInfo() {
    GridInfo grid = null;
    for (String arg : args) {
      if (arg.startsWith("grid:")) {
        grid = new GridInfo();
        grid.fromText(new Text(arg.substring(arg.indexOf(':')+1)));
      }
    }
    return grid;
  }

  public Point getPoint() {
    Point point = null;
    for (String arg : args) {
      if (arg.startsWith("point:")) {
        point = new Point();
        point.fromText(new Text(arg.substring(arg.indexOf(':')+1)));
      }
    }
    return point;
  }

  public boolean isRtree() {
    return is("rtree");
  }

  public boolean isPack() {
    return is("pack");
  }
  
  public boolean isOverwrite() {
    return is("overwrite");
  }
  
  public long getSize() {
    for (String arg : args) {
      if (arg.startsWith("size:")) {
        String size_str = arg.split(":")[1];
        if (size_str.indexOf('.') == -1)
          return Long.parseLong(size_str);
        String[] size_parts = size_str.split("\\.", 2);
        long size = Long.parseLong(size_parts[0]);
        size_parts[1] = size_parts[1].toLowerCase();
        if (size_parts[1].startsWith("k"))
          size *= 1024;
        else if (size_parts[1].startsWith("m"))
          size *= 1024 * 1024;
        else if (size_parts[1].startsWith("g"))
          size *= 1024 * 1024 * 1024;
        else if (size_parts[1].startsWith("t"))
          size *= 1024 * 1024 * 1024 * 1024;
        return size;
      }
    }
    return 0;
  }

  public boolean isRandom() {
    return is("random");
  }
  
  public boolean isLocal() {
    return is("local");
  }
  
  protected boolean is(String flag) {
    String expected_arg = "-"+flag;
    for (String arg : args) {
      if (arg.equals(expected_arg))
        return true;
    }
    return false;
  }

  public int getCount() {
    for (String arg : args) {
      if (arg.startsWith("count:")) {
        return Integer.parseInt(arg.substring(arg.indexOf(':')+1));
      }
    }
    return 1;
  }
  
  public int getK() {
    for (String arg : args) {
      if (arg.startsWith("k:")) {
        return Integer.parseInt(arg.substring(arg.indexOf(':')+1));
      }
    }
    return 0;
  }

  public float getSelectionRatio() {
    for (String arg : args) {
      if (arg.startsWith("ratio:")) {
        return Float.parseFloat(arg.substring(arg.indexOf(':')+1));
      }
    }
    return -1.0f;
  }

  public int getConcurrency() {
    for (String arg : args) {
      if (arg.startsWith("concurrency:")) {
        return Integer.parseInt(arg.substring(arg.indexOf(':')+1));
      }
    }
    return Integer.MAX_VALUE;
  }

  public long getBlockSize() {
    for (String arg : args) {
      if (arg.startsWith("blocksize:") || arg.startsWith("block_size:")) {
        String size_str = arg.split(":")[1];
        if (size_str.indexOf('.') == -1)
          return Long.parseLong(size_str);
        String[] size_parts = size_str.split("\\.", 2);
        long size = Long.parseLong(size_parts[0]);
        size_parts[1] = size_parts[1].toLowerCase();
        if (size_parts[1].startsWith("k"))
          size *= 1024;
        else if (size_parts[1].startsWith("m"))
          size *= 1024 * 1024;
        else if (size_parts[1].startsWith("g"))
          size *= 1024 * 1024 * 1024;
        else if (size_parts[1].startsWith("t"))
          size *= 1024 * 1024 * 1024 * 1024;
        return size;
      }
    }
    return 0;
  }
  
  /**
   * 
   * @param autodetect - Automatically detect shape type from input file
   *   if shape is not explicitly set by user
   * @return
   */
  public Shape getShape(boolean autodetect) {
    final Text shapeType = new Text();
    for (String arg : args) {
      if (arg.startsWith("shape:")) {
        byte[] temp = arg.substring(arg.indexOf(':')+1).toLowerCase().getBytes();
        shapeType.set(temp);
      }
    }
    
    if (autodetect && shapeType.getLength() == 0) {
      // Shape type not found in parameters. Try to infer from a line in input
      // file
      Path in_file = getPath();
      try {
        Sampler.sampleLocal(in_file.getFileSystem(new Configuration()), in_file, 1, new OutputCollector<LongWritable, Text2>() {
          @Override
          public void collect(LongWritable key, Text2 value) throws IOException {
            String val = value.toString();
            String[] parts = val.split(",");
            if (parts.length == 2) {
              shapeType.set("point".getBytes());
            } else if (parts.length == 4) {
              shapeType.set("rect".getBytes());
            } else if (parts.length > 4) {
              shapeType.set("tiger".getBytes());
            }
          }
        }, new Text2());
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    Shape stockShape = null;
    if (shapeType.toString().startsWith("rect")) {
      stockShape = new Rectangle();
    } else if (shapeType.toString().startsWith("point")) {
      stockShape = new Point();
    } else if (shapeType.toString().startsWith("tiger")) {
      stockShape = new TigerShape();
    } else {
      LOG.warn("unknown shape type: "+shapeType);
    }
    
    return stockShape;
  }
}
