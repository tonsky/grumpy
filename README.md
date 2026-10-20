# Minimalistic blog engine

[Grumpy.website](https://grumpy.website/)

## Runtime dependencies

- JDK 21+
- Clojure CLI
- ImageMagick (`magick` or `convert`)
- ffmpeg (`ffmpeg`, `ffprobe`)

## Running

```
./script/run.sh
```

Env:

- `DATA_DIR`: where `config.edn`, `db.sqlite`, media, search index and stats live. Default `grumpy_data` (relative to cwd)
- `PORT`: HTTP port, default 8080 (`--port` overrides it)

`GET /up` is a health check.

## Development

```
mkdir grumpy_data
./script/bootstrap.sh
./script/repl.sh
open http://localhost:8080
```
