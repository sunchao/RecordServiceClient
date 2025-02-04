/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloudera.recordservice.hcatalog.mapreduce;

import com.cloudera.recordservice.hcatalog.common.HCatRSUtil;
import com.cloudera.recordservice.mapreduce.RecordServiceInputFormat;
import com.cloudera.recordservice.mapreduce.RecordServiceInputSplit;
import com.cloudera.recordservice.mr.RecordServiceRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.metadata.HiveStorageHandler;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hive.hcatalog.common.HCatConstants;
import org.apache.hive.hcatalog.common.HCatUtil;
import org.apache.hive.hcatalog.data.schema.HCatSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** The HCat wrapper for the underlying RecordReader,
 * this ensures that the initialize on
 * the underlying record reader is done with the underlying split,
 * not with HCatSplit.
 */
class HCatRecordReader extends RecordReader<WritableComparable, RecordServiceRecord> {

  private static final Logger LOG = LoggerFactory.getLogger(HCatRecordReader.class);

  /** The underlying record reader to delegate to. */
  private RecordReader<NullWritable, RecordServiceRecord> baseRecordReader;

  /**
   * Instantiates a new hcat record reader.
   */
  public HCatRecordReader() {
  }

  /* (non-Javadoc)
   * @see org.apache.hadoop.mapreduce.RecordReader#initialize(
   * org.apache.hadoop.mapreduce.InputSplit,
   * org.apache.hadoop.mapreduce.TaskAttemptContext)
   */
  @Override
  public void initialize(org.apache.hadoop.mapreduce.InputSplit split,
               TaskAttemptContext taskContext) throws IOException, InterruptedException {
    RecordServiceInputSplit recordServiceSplit = (RecordServiceInputSplit) split;
    baseRecordReader = createBaseRecordReader(recordServiceSplit, taskContext);
  }

  private RecordReader<NullWritable, RecordServiceRecord> createBaseRecordReader(RecordServiceInputSplit recordServiceSplit,
                                     TaskAttemptContext taskContext) throws IOException {
    JobConf jobConf = HCatUtil.getJobConfFromContext(taskContext);
    HCatRSUtil.copyCredentialsToJobConf(taskContext.getCredentials(), jobConf);
    RecordServiceInputFormat inputFormat = ReflectionUtils.newInstance(RecordServiceInputFormat.class, jobConf);
    try {
      return inputFormat.createRecordReader(recordServiceSplit, taskContext);
    }
    catch (InterruptedException e){
      LOG.error("Unable to create Record Reader", e);
      return null;
    }
  }

  /* (non-Javadoc)
  * @see org.apache.hadoop.mapreduce.RecordReader#getCurrentKey()
  */
  @Override
  public WritableComparable getCurrentKey()
    throws IOException, InterruptedException {
    return baseRecordReader.getCurrentKey();
  }

  /* (non-Javadoc)
   * @see org.apache.hadoop.mapreduce.RecordReader#getCurrentValue()
   */
  @Override
  public RecordServiceRecord getCurrentValue() throws IOException, InterruptedException {
    return baseRecordReader.getCurrentValue();
  }

  /* (non-Javadoc)
   * @see org.apache.hadoop.mapreduce.RecordReader#getProgress()
   */
  @Override
  public float getProgress() {
    try {
      return baseRecordReader.getProgress();
    } catch (IOException e) {
      LOG.warn("Exception in HCatRecord reader", e);
    }
    catch (InterruptedException e) {
      LOG.warn("Exception in HCatRecord reader", e);
    }
    return 0.0f; // errored
  }

  /**
   * Check if the wrapped RecordReader has another record, and if so convert it into an
   * HCatRecord. We both check for records and convert here so a configurable percent of
   * bad records can be tolerated.
   *
   * @return if there is a next record
   * @throws IOException on error
   * @throws InterruptedException on error
   */
  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    return baseRecordReader.nextKeyValue();
  }

  /* (non-Javadoc)
  * @see org.apache.hadoop.mapreduce.RecordReader#close()
  */
  @Override
  public void close() throws IOException {
    baseRecordReader.close();
  }

  /**
   * Tracks number of of errors in input and throws a Runtime exception
   * if the rate of errors crosses a limit.
   * <br/>
   * The intention is to skip over very rare file corruption or incorrect
   * input, but catch programmer errors (incorrect format, or incorrect
   * deserializers etc).
   *
   * This class was largely copied from Elephant-Bird (thanks @rangadi!)
   * https://github.com/kevinweil/elephant-bird/blob/master/core/src/main/java/com/twitter/elephantbird/mapreduce/input/LzoRecordReader.java
   */
  static class InputErrorTracker {
    long numRecords;
    long numErrors;

    double errorThreshold; // max fraction of errors allowed
    long minErrors; // throw error only after this many errors

    InputErrorTracker(Configuration conf) {
      errorThreshold = conf.getFloat(HCatConstants.HCAT_INPUT_BAD_RECORD_THRESHOLD_KEY,
        HCatConstants.HCAT_INPUT_BAD_RECORD_THRESHOLD_DEFAULT);
      minErrors = conf.getLong(HCatConstants.HCAT_INPUT_BAD_RECORD_MIN_KEY,
        HCatConstants.HCAT_INPUT_BAD_RECORD_MIN_DEFAULT);
      numRecords = 0;
      numErrors = 0;
    }

    void incRecords() {
      numRecords++;
    }

    void incErrors(Throwable cause) {
      numErrors++;
      if (numErrors > numRecords) {
        // incorrect use of this class
        throw new RuntimeException("Forgot to invoke incRecords()?");
      }

      if (cause == null) {
        cause = new Exception("Unknown error");
      }

      if (errorThreshold <= 0) { // no errors are tolerated
        throw new RuntimeException("error while reading input records", cause);
      }

      LOG.warn("Error while reading an input record ("
        + numErrors + " out of " + numRecords + " so far ): ", cause);

      double errRate = numErrors / (double) numRecords;

      // will always excuse the first error. We can decide if single
      // error crosses threshold inside close() if we want to.
      if (numErrors >= minErrors && errRate > errorThreshold) {
        LOG.error(numErrors + " out of " + numRecords
          + " crosses configured threshold (" + errorThreshold + ")");
        throw new RuntimeException("error rate while reading input records crossed threshold", cause);
      }
    }
  }
}
