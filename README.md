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

Every setting is read from the `--name value` command-line arg first, then from the `NAME` env var:

| arg                  | env                | default                  |
|----------------------|--------------------|--------------------------|
| `--data-dir`         | `DATA_DIR`         | `grumpy_data` (relative to cwd): db, media, search index, stats |
| `--host`             | `HOST`             | `localhost`              |
| `--port`             | `PORT`             | `8080`                   |
| `--site-url`         | `SITE_URL`         | `https://grumpy.website` (`http://localhost:8080` turns on dev mode) |
| `--page-size`        | `PAGE_SIZE`        | `10`                     |
| `--cookie-secret`    | `COOKIE_SECRET`    | random on every start; base64 of 16 bytes |
| `--telegram-token`   | `TELEGRAM_TOKEN`   | no crossposting, no login links |
| `--telegram-channel` | `TELEGRAM_CHANNEL` | no crossposting          |
| `--mastodon-token`   | `MASTODON_TOKEN`   | no crossposting          |
| `--forced-user`      | `FORCED_USER`      | log everyone in as this user (dev) |

`GET /up` is a health check.

## Development

```
mkdir grumpy_data
./script/repl.sh   # sets SITE_URL, FORCED_USER, HOST for dev
open http://localhost:8080
```
