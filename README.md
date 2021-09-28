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

akka-p2p>
```

#### Connect to a peer

Connect to a peer with the `connect` command

```bash
$ sbt -error run

akka-p2p> connect localhost:3001

akka-p2p>
```

And send a message with the `send` command

```bash
akka-p2p> send localhost:3001 hi from localhost:3002
Attempting to send message "hi from localhost:3002" to peer at localhost:3001

akka-p2p>
```

Your peer will see

```bash
akka-p2p> localhost:3002: "hi from localhost:3002"

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

akka-p2p>
```