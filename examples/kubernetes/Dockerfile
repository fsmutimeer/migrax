# Your migrations in an image, on top of the Migrax image. Build it with your application:
#   docker build -f examples/kubernetes/Dockerfile -t registry.example.com/shop-migrations:1.0 .
FROM ghcr.io/fsmutimeer/migrax:0.3.0
COPY src/main/resources/db/migration/ /migrations/
# The snapshot lets 'migrax drift' compare the database with what the migrations produce.
COPY .migrax/snapshot.json /workspace/.migrax/snapshot.json
