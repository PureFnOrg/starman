# starman

A Redis implementation of the Bridges protocols.

![Starman](http://i.imgur.com/7cu2i5T.jpg?1)

## What does it do?

Two component implementations of redis clients are included in this project.
Carmine (https://github.com/ptaoussanis/carmine) is the original
implementation. However, beginning in Aug 2018 carmine's internal connection
pool strategy began to inexplicably fail during high-volume ETL operations,
routinely filling up the OS's transient TCP port space with 20k+ connections
sitting in `TIME_WAIT` from both `GET` and `SET` operations.

Jedis was explored as an alternative, and it's connection pooling appears to
actually work.

### Carmine

https://github.com/ptaoussanis/carmine

#### Features

- Supports out-of-the-box nippy encoding for Clojure data structures, but also
  uses it's own serialization mechanism including custom carmine specific headers.
  (Not good)

- Optimistic locking in `swap-in` by using `WATCH`

- Automatic retries of failed commands (e.g. `swap-in`)

- A whole host of other Redis features which we don't use.

### Jedis

Jedis is fully featured, but only a small portion is wrapped.  Current
implementation is bare-bones and not considered production ready except for
high-volume ETLs which only use `GET`, `SET` and `DEL`.

#### Features

- Optimistic locking with `WATCH`

- Automatic retries of failed commands (e.g. `swap-in`)

- Everything is stringly typed.

#### TODO

- Decide on serialization strategy.
  - Similar to [lebowski](https://github.com/theladders/lebowski) with
    declarative namespaces and encoding in the config?
  - Something at runtime like carmine but not as bad?

## Development

You can interact with the library in the REPL by typing in Emacs:

    M-x cider-jack-in
    user> 

Initialize the components by: 

    user> (dev)
    dev> (reset)


## Deploying 

First, setup your GPG credentials and Leiningen environment.

See these for details:

https://github.com/technomancy/leiningen/blob/master/doc/GPG.md
https://github.com/technomancy/leiningen/blob/master/doc/DEPLOY.md#authentication

### TL;DR

To release a snapshot:

    $ lein release :patch

To release a minor version:

    $ lein release :minor
    
To release a major version:

    $ lein release :major
