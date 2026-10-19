CREATE TABLE invoice (id BIGINT PRIMARY KEY, owner VARCHAR(40));
INSERT INTO invoice (id, owner) VALUES (1, '${owner}');
