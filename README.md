# akka-p2p

A minimal peer-to-peer server written in Scala, using Akka Streams.

## Try it

### Locally

First, download _akka-p2p_ using `git`

```bash
$ git clone https://github.com/awwsmm/akka-p2p.git
Cloning into 'akka-p2p'...
remote: Enumerating objects: 103, done.
remote: Counting objects: 100% (103/103), done.
remote: Compressing objects: 100% (71/71), done.
remote: Total 103 (delta 33), reused 91 (delta 21), pack-reused 0
Receiving objects: 100% (103/103), 22.34 KiB | 693.00 KiB/s, done.
Resolving deltas: 100% (33/33), done.
```

Then, `cd` into the `akka-p2p` directory

```bash
$ cd akka-p2p
```

Then, `run` with `sbt`

```bash
$ sbt -error run
2021-09-24 @ 18:37:47.777  INFO | Slf4jLogger started
2021-09-24 @ 18:37:48.365  INFO | Listening on localhost:3001

akka-p2p> help
broadcast => broadcast a message to all peers
connect => connects to a peer
disconnect => disconnects from a peer
help => prints this help text
logout => disconnects from all peers
quit => quits zepto
send => send a message to a peer

akka-p2p> 
```

> The `-error` flag hides all compilation output except for errors. Omitting this flag will show compilation log lines.

Open another terminal and create a second _akka-p2p_ instance with `sbt run` again. (But first, you'll need to change the port.)

```bash
$ cd akka-p2p

$ export AKKA_P2P_HTTP_PORT=3002

$ sbt -error run
2021-09-24 @ 18:51:14.309  INFO | Slf4jLogger started
2021-09-24 @ 18:51:14.912  INFO | Listening on localhost:3002

akka-p2p>
```

#### Connect to a peer

Connect to a peer with the `connect` command

```bash
$ sbt -error run
2021-09-24 @ 18:51:14.309  INFO | Slf4jLogger started
2021-09-24 @ 18:51:14.912  INFO | Listening on localhost:3002

akka-p2p> connect localhost:3001
2021-09-24 @ 18:52:41.597 DEBUG | Attempting to connect to peer at localhost:3001

akka-p2p> 2021-09-24 @ 18:52:41.606 DEBUG | Disconnected Peer at localhost:3001 received Command to RequestConnection
2021-09-24 @ 18:52:42.125  INFO | Successfully connected to localhost:3001
2021-09-24 @ 18:52:42.125  INFO | Registering connected peer at localhost:3001

akka-p2p>
```

And send a message with the `send` command

```bash
akka-p2p> send localhost:3001 hi from localhost:3002
2021-09-24 @ 18:53:31.288 DEBUG | Attempting to send message "hi from localhost:3002" to peer at localhost:3001

akka-p2p> 2021-09-24 @ 18:53:31.292 DEBUG | Sending message "hi from localhost:3002" to localhost:3001

akka-p2p>
```

Your peer will see

```bash
akka-p2p> 2021-09-24 @ 18:52:41.963  INFO | Received p2p connection request from localhost:3002
2021-09-24 @ 18:52:41.967 DEBUG | Attempting to accept incoming connection from localhost:3002
2021-09-24 @ 18:52:41.978 DEBUG | Disconnected Peer at localhost:3002 received Command to AcceptConnection
2021-09-24 @ 18:52:42.057  INFO | Registering connected peer at localhost:3002
2021-09-24 @ 18:53:31.307 DEBUG | Received incoming message "hi from localhost:3002" from localhost:3002
2021-09-24 @ 18:53:31.319  INFO | hi from localhost:3002

akka-p2p>
```


## Troubleshooting

### `ERROR | Bind failed for TCP channel on endpoint [localhost/127.0.0.1:3001]`

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