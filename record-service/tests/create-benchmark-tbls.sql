-- Create databases
CREATE DATABASE IF NOT EXISTS tpch6gb;
CREATE DATABASE IF NOT EXISTS tpch6gb_parquet;

-- Create tables
DROP TABLE IF EXISTS tpch6gb.lineitem;
CREATE EXTERNAL TABLE tpch6gb.lineitem(
  l_orderkey BIGINT,
  l_partkey BIGINT,
  l_suppkey BIGINT,
  l_linenumber INT,
  l_quantity DECIMAL(12,2),
  l_extendedprice DECIMAL(12,2),
  l_discount DECIMAL(12,2),
  l_tax DECIMAL(12,2),
  l_returnflag STRING,
  l_linestatus STRING,
  l_shipdate STRING,
  l_commitdate STRING,
  l_receiptdate STRING,
  l_shipinstruct STRING,
  l_shipmode STRING,
  l_comment STRING
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '|'
STORED AS TEXTFILE
LOCATION '/test-warehouse/tpch6gb.lineitem';

DROP TABLE IF EXISTS tpch6gb_parquet.lineitem;
CREATE EXTERNAL TABLE tpch6gb_parquet.lineitem like tpch6gb.lineitem
STORED AS PARQUET
LOCATION '/test-warehouse/tpch6gb_parquet.lineitem';
