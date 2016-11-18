CREATE TABLE IF NOT EXISTS nakadi_partition (

    np_consumer_name  text NOT NULL,
    np_event_name     text NOT NULL,
    np_partition      text NOT NULL,
    np_locked_by      text,
    np_waiting        text[],
    np_last_modified  timestamp without time zone NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (np_consumer_name, np_event_name, np_partition)
);
