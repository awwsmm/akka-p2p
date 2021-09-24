# akka-p2p

A minimal peer-to-peer server written in Scala, using Akka Streams.

## Try it

### Locally

First, download _akka-p2p_


## Troubleshooting

# `ERROR | Bind failed for TCP channel on endpoint [localhost/127.0.0.1:3001]`

If you're seeing the above error, it means the port you're trying to use for _akka-p2p_ (in this case `3001`) is already in use by another process.

You can set a custom port for _akka-p2p_ with

```bash
$ export AKKA_P2P_HTTP_PORT=5555 # or whatever
```

Then just run _akka-p2p_ as normal

```bash
$ sbt run
...
2021-09-24 @ 18:09:00.290  INFO | Slf4jLogger started
2021-09-24 @ 18:09:00.776  INFO | Listening on localhost:5555

akka-p2p>
```