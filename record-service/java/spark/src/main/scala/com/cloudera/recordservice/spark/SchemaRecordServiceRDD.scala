/*
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

package com.cloudera.recordservice.spark

import java.lang.reflect.Method

import com.cloudera.recordservice.thrift._
import org.apache.spark._

import scala.reflect.ClassTag
import scala.util.control.Breaks

/**
 * RDD that is backed by the RecordService. This returns an RDD of case class objects.
 * The caller passes the case class that they'd like to use. This class uses reflection
 * to populate the case class objects.
 *
 * Example:
 * case class Nation(var key:Long, var name:String)
 * val data:RDD[Nation] = sc.recordServiceRecords[Nation]("tpch.nation")
 *
 * The schema and specified case class can be resolved either by ordinal or by name.
 *
 * If by ordinal, the ith field of the case class must match the type of the ith field of
 * the RecordService record. i.e. the case class types has to be a prefix of the query's
 * result types.
 * The names of the field in the case class are ignored.
 *
 * If by name, every field in the case class must exist in the query's result and the
 * types of those fields must match. Matching is case insensitive.
 *
 * TODO: Why doesn't classOf[T] work (and then you don't need to
 * pass the recordClass arg)
 * TODO: metrics
 * TODO: think about NULLs some more.
 */
class SchemaRecordServiceRDD[T:ClassTag](sc: SparkContext,
                                         recordClass:Class[T],
                                         byOrdinal:Boolean = false,
                                         plannerHost: String = "localhost")
    extends RecordServiceRDDBase[T](sc, plannerHost) with Logging {

  override def setTable(table:String) = {
    if (byOrdinal) {
      // TODO: add API to RecordService to get the table schema so we can do projection
      this.stmt = "SELECT * from " + table
    } else {
      val sb = new StringBuilder("SELECT ")
      for (i <- 0 until fields.length) {
        if (i != 0) sb.append(",")
        sb.append(" " + fields(i))
      }
      sb.append(" FROM " + table)
      this.stmt = sb.toString()
    }
    this
  }

  override def setStatement(stmt:String) = {
    super.setStatement(stmt)
    this
  }

  /**
   * Sets v as the default record. For fields that are non-nullable but the data
   * contained NULL, the field is instead populated from v.
   */
  def setDefaultValue(v:T) = {
    defaultVal = Some(v)
    this
  }

  /**
   * If true, records containing unhandled (field is not nullable and no default value)
   * null fields are ignored. Otherwise, the task is aborted.
   */
  def setIgnoreUnhandledNull(v:Boolean) = {
    ignoreUnhandledNull = v
    this
  }

  var fields:Array[String] = extractFields()
  var types:Array[TTypeId] = extractTypes()

  var defaultVal:Option[T] = None
  var ignoreUnhandledNull:Boolean = false

  private def extractFields() = {
    val f = recordClass.getDeclaredFields()
    val result = new Array[String](f.size)
    val allMethods = recordClass.getMethods()

    for (i <- 0 until f.length) {
      result(i) = f(i).getName()

      // Verify that the field is declared as 'var'. This means there is a
      // generated field_$eq method.
      val setter = f(i).getName + "_$eq"
      if (allMethods.find(_.getName() == setter) == None) {
        throw new SparkException("Incompatible Schema. Fields in case class " +
          "must be 'var'. Incorrect field: " + result(i))
      }
    }
    result
  }

  private def extractTypes() = {
    val f = recordClass.getDeclaredFields()
    val result = new Array[TTypeId](f.size)
    for (i <- 0 until f.length) {
      if (f(i).getType.getName == "boolean") {
        result(i) = TTypeId.BOOLEAN
      } else if (f(i).getType.getName == "byte") {
        result(i) = TTypeId.TINYINT
      } else if (f(i).getType.getName == "char") {
        result(i) = TTypeId.TINYINT
      } else if (f(i).getType.getName == "short") {
        result(i) = TTypeId.SMALLINT
      } else if (f(i).getType.getName == "int") {
        result(i) = TTypeId.INT
      } else if (f(i).getType.getName == "long") {
        result(i) = TTypeId.BIGINT
      } else if (f(i).getType.getName == "float") {
        result(i) = TTypeId.FLOAT
      } else if (f(i).getType.getName == "double") {
        result(i) = TTypeId.DOUBLE
      } else if (f(i).getType.getName == "java.lang.String") {
        result(i) = TTypeId.STRING
      } else {
        throw new SparkException("Case class uses types that are unsupported. " +
          "Only basic types and String are supported. Type=" + f(i).getType().getName())
      }
    }
    result
  }

  private def printSchema(schema:TSchema) = {
    val builder:StringBuilder = new StringBuilder("schema: {\n")
    for (i <- 0 until schema.cols.size()) {
      builder.append("  ")
             .append(schema.cols.get(i).name)
             .append(":")
             .append(schema.cols.get(i).getType.type_id)
             .append("\n")
    }
    builder.append("}")
    builder.toString()
  }

  private def verifySchema(schema: TSchema) = {
    if (schema.cols.size() < fields.length) {
      // TODO: default values?
      throw new SparkException("Schema mismatch. Cannot match if the case class " +
        " contains more fields than the table")
    }

    if (byOrdinal) {
      for (i <- 0 until fields.length) {
        if (types(i) != schema.cols.get(i).getType.type_id) {
          throw new SparkException("Schema mismatch. The type of field '" + fields(i) +
            "' does not match the result type. " +
             "Expected type: " + types(i) + " Actual type: " +
            schema.cols.get(i).getType.type_id)
        }
      }
    } else {
      for (i <- 0 until fields.length) {
        for (j <- 0 until i) {
          if (fields(i).equalsIgnoreCase(fields(j))) {
            throw new SparkException("Invalid case class. When matching by name, " +
              "fields cannot have the same case-insensitive name")
          }
        }

        var found = false
        for (j <- 0 until schema.cols.size()) {
          if (fields(i).equalsIgnoreCase(schema.cols.get(j).name)) {
            found = true
            if (types(i) != schema.cols.get(j).getType.type_id) {
              throw new SparkException("Schema mismatch. The type of field '" +
                fields(i) + "' does not match the result type. " +
                "Expected type: " + types(i) + " Actual type: " +
                schema.cols.get(j).getType.type_id)
            }
          }
        }
        if (!found) {
          // TODO: print schema
          throw new SparkException("Schema mismatch. Field in case class '" + fields(i) +
            "' did not match any field in the result schema:\n" + printSchema(schema))
        }
      }
    }
  }

  // Creates an object of type T, using reflection to call the constructor.
  private def createObject() : T = {
    val ctor = recordClass.getConstructors()(0)
    val numArgs = ctor.getParameterTypes().size
    val args = new Array[AnyRef](numArgs)
    for (i <- 0 until numArgs) {
      if (ctor.getParameterTypes()(i).getName == "boolean") {
        args(i) = new java.lang.Boolean(false)
      } else if (ctor.getParameterTypes()(i).getName == "byte") {
        args(i) = new java.lang.Byte(0.toByte)
      } else if (ctor.getParameterTypes()(i).getName == "char") {
        args(i) = new Character('0')
      } else if (ctor.getParameterTypes()(i).getName == "short") {
        args(i) = new java.lang.Short(0.toShort)
      } else if (ctor.getParameterTypes()(i).getName == "int") {
        args(i) = new java.lang.Integer(0)
      } else if (ctor.getParameterTypes()(i).getName == "long") {
        args(i) = new java.lang.Long(0)
      } else if (ctor.getParameterTypes()(i).getName == "float") {
        args(i) = new java.lang.Float(0)
      } else if (ctor.getParameterTypes()(i).getName == "double") {
        args(i) = new java.lang.Double(0)
      } else if (ctor.getParameterTypes()(i).getName == "java.lang.String") {
        args(i) = new String("")
      } else {
        throw new RuntimeException("Unsupported type: " +
            ctor.getParameterTypes()(i).getName)
      }
    }
    ctor.newInstance(args:_*).asInstanceOf[T]
  }

  private class RecordServiceIterator(partition: RecordServicePartition)
      extends NextIterator[T] {
    // The object to return in getNext(). We always return the same object
    // and just update the value for each record.
    var value:T = createObject()

    // The array of setters to populate 'value'. This is always indexed by the ordinal
    // returned by the record service.
    var setters:Array[Method] = new Array[Method](partition.schema.cols.size())

    // Getters for each of the fields.
    var getters:Array[Method] = new Array[Method](partition.schema.cols.size())

    // Default values for each field. Only used/populated if defaultVal is set.
    var defaultVals:Array[AnyRef] = new Array[AnyRef](partition.schema.cols.size())

    val allMethods = value.getClass.getMethods()

    // TODO: try to dedup some of this code.
    if (byOrdinal) {
      val declaredFields = value.getClass.getDeclaredFields()
      for (i <- 0 until declaredFields.length) {
        val setter = declaredFields(i).getName + "_$eq"
        val setterMethod = allMethods.find(_.getName() == setter)
        val getterMethod = allMethods.find(_.getName() == declaredFields(i).getName )
        assert (setterMethod != None)
        assert (getterMethod != None)
        setters(i) = setterMethod.get
        getters(i) = getterMethod.get
      }
    } else {
      // Resolve the order of cols. e.g. the result from the record service could be
      // { name, key } but the case class is
      // { key, name }.
      // We know from earlier validation that the case class has to be a subset of
      // the result from the record service.
      // TODO: it should be equal to the record service, we should do additional
      // projection for the client.
      for (i <- 0 until partition.schema.cols.size()) {
        val resultColName = partition.schema.cols.get(i).name
        for (j <- 0 until fields.length) {
          if (resultColName.equalsIgnoreCase(fields(j))) {
            val setter = fields(j) + "_$eq"
            val setterMethod = allMethods.find(_.getName() == setter)
            val getterMethod = allMethods.find(_.getName() == fields(j))
            assert (setterMethod != None)
            assert (getterMethod != None)
            setters(i) = setterMethod.get
            getters(i) = getterMethod.get
          }
        }
      }
    }

    if (defaultVal.isDefined) {
      for (i <- 0 until getters.size) {
        defaultVals(i) = getters(i).invoke(defaultVal.get)
      }
    }

    var (worker, records) = execTask(partition)

    override def getNext() : T = {
      while (true) {
        if (!records.hasNext()) {
          finished = true
          return value
        }

        // Reconstruct the record
        val record = records.next()
        val loop = new Breaks
        loop.breakable {
          for (i <- 0 until setters.length) {
            if (setters(i) != null) {
              assert(getters(i) != null)
              val v = if (record.isNull(i)) {
                if (defaultVal.isEmpty) {
                  // TODO: this really needs to be collected with metrics. How do you do
                  // this in spark? Accumulators?
                  if (ignoreUnhandledNull) loop.break

                  // TODO: allow nullable case classes. This seems to require scala 2.11
                  // (we normally run 2.10) to get the reflection to work.
                  // TODO: add a mode where these records are just ignored with some metrics
                  // on how many are ignored.
                  throw new SparkException(
                    "Data contained NULLs but no default value provided.")
                }
                defaultVals(i)
              } else {
                // TODO: make sure this is the cheapest way to do this and we're not doing
                // unnecessary boxing
                partition.schema.cols.get(i).getType().type_id match {
                  case TTypeId.BOOLEAN =>
                    record.getBoolean(i): java.lang.Boolean
                  case TTypeId.TINYINT =>
                    // TODO: does this work? We probably need to cast it to Byte or Char
                    record.getByte(i): java.lang.Byte
                  case TTypeId.SMALLINT =>
                    record.getShort(i): java.lang.Short
                  case TTypeId.INT =>
                    record.getInt(i): java.lang.Integer
                  case TTypeId.BIGINT =>
                    record.getLong(i): java.lang.Long
                  case TTypeId.FLOAT =>
                    record.getFloat(i): java.lang.Float
                  case TTypeId.DOUBLE =>
                    record.getDouble(i): java.lang.Double
                  case TTypeId.STRING =>
                    record.getByteArray(i).toString()
                  case _ =>
                    assert(false)
                    None
                }
              }
              setters(i).invoke(value, v)
            }
          }
          return value
        }
      }
      return value
    }

    override def close() = {
      if (records != null) {
        records.close()
        records = null
      }
      if (worker != null) {
        worker.close()
        worker = null
      }
    }
  }

  /**
   * Executes the task against the RecordServiceWorker and returns an iterator to fetch
   * result for the entire task.
   */
  override def compute(split: Partition, context: TaskContext):
      InterruptibleIterator[T] = {
    new InterruptibleIterator[T](context,
        new RecordServiceIterator(split.asInstanceOf[RecordServicePartition]))
  }

  /**
   * Sends the request to the RecordServicePlanner to generate the list of partitions
   * (tasks in RecordService terminology)
   * TODO: How does this handle locality.
   */
  override protected def getPartitions: Array[Partition] = {
    val (request, partitions) = planRequest
    // TODO: verify that T is not an inner class, Spark shell generates it that way.
    verifySchema(request.schema)
    logInfo("Schema matched")
    partitions
  }
}
