package edu.umn.cs.spatialHadoop.mapReduce;

import java.io.IOException;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.spatial.CellInfo;
import org.apache.hadoop.spatial.GridInfo;
import org.apache.hadoop.spatial.TigerShape;

public class RTreeGridRecordWriter extends org.apache.hadoop.spatial.RTreeGridRecordWriter implements RecordWriter<CellInfo, TigerShape> {

  public RTreeGridRecordWriter(FileSystem fileSystem, Path outFile, GridInfo gridInfo, CellInfo[] cells) throws IOException {
    super(fileSystem, outFile, gridInfo, cells);
  }

  @Override
  public void close(Reporter reporter) throws IOException {
    super.close();
  }
}
