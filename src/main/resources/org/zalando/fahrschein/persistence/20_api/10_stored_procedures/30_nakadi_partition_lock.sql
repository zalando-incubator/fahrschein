CREATE OR REPLACE FUNCTION nakadi_partition_lock(p_consumer_name text, p_event_name text, p_partitions text[], p_locked_by text) RETURNS TABLE(consumer_name text, event_name text, partition text) AS
$$
BEGIN

    LOCK TABLE nakadi_partition;

    INSERT INTO nakadi_partition (np_consumer_name, np_event_name, np_partition)
    SELECT p_consumer_name, p_event_name, p.partition
      FROM unnest(p_partitions) AS p(partition)
     WHERE NOT EXISTS (SELECT 1
                         FROM nakadi_partition
                        WHERE np_consumer_name = p_consumer_name
                          AND np_event_name = p_event_name
                          AND np_partition = p.partition);

    UPDATE nakadi_partition np
       SET np_waiting = np_waiting || ARRAY[p_locked_by],
           np_last_modified = statement_timestamp()
      FROM unnest(p_partitions) p(partition)
     WHERE np_consumer_name = p_consumer_name
       AND np_event_name = p_event_name
       AND np_partition = p.partition
       AND np_locked_by IS NOT NULL
       AND np_locked_by <> p_locked_by
       AND (np_waiting IS NULL OR NOT p_locked_by = ANY(np_waiting));

    RETURN QUERY
    UPDATE nakadi_partition np
       SET np_locked_by = p_locked_by,
           np_last_modified = statement_timestamp()
      FROM unnest(p_partitions) p(partition)
     WHERE np_consumer_name = p_consumer_name
       AND np_event_name = p_event_name
       AND np_partition = p.partition
       AND (np_locked_by IS NULL OR np_locked_by = p_locked_by)
    RETURNING np_consumer_name, np_event_name, np_partition;
END
$$ LANGUAGE 'plpgsql' VOLATILE SECURITY DEFINER;