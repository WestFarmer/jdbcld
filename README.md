# jdbcld
A Java agent to find JDBC connection leak

## why
  1. It's always tricky to find resource leak problem in java, easy to notice problems, but hard to track down to the real cause. Usally people will find unclosed sessions at database side, then extract sqls that session has submited, then try to find suspect code by those sqls.
  2. Connection pool libraries can help to prevent leak, but again can't help to trace down to real cause, even though some connection pool libraries has such feature, switch to them may not be a option for some people.

## how
  jdbcld use java instrumention, run as a side agent, so it will not 

## usage

add below to you jvm startup arguments:

`-javaagent:jdbcld.jar=log_dir,log_level,connection_class`

    - log_dir: where to store jdbcld logs
    - log_level: jdbcld log level
    - connection_class: the target class for instrumention, see below for more detail.
