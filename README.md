# Tusovochka

Self-hosted social network with friends, walls, groups, photo albums, private messages and events. One server, one community: there is no federation, nothing is ever sent to or fetched from other servers.

## About this project

Tusovochka is a fork of [Smithereen](https://github.com/grishka/Smithereen) by [Gregory Klyushnikov (@grishka)](https://github.com/grishka). Smithereen is an excellent old-school social network engine, and all of the design, the data model, the web UI and the vast majority of the code in this repository are his work. Smithereen is released into the public domain under [the Unlicense](UNLICENSE), and so is this fork. The original README asks forks to change the name, which is why this project is called Tusovochka rather than Smithereen.

The one big difference from upstream is the removal of ActivityPub federation. Smithereen can talk to Mastodon and other fediverse servers; Tusovochka cannot and never will. Everything that existed only for that purpose is gone: inboxes and outboxes, HTTP signatures, JSON-LD processing, remote actor and object fetching, caching of other servers' media, domain blocks and account migration. The goal is a smaller attack surface and a simpler server for a closed community where every user, group and post lives on this one installation.

Because of that, Tusovochka does not track upstream Smithereen releases. Bug fixes from upstream are ported by hand when they apply.

The Java packages, the jar file, the database name and the systemd service are still called `smithereen`, so that existing installations and upgrade scripts keep working. Only the product name changed.

## Building and installation

### Requirements

- JDK 21 or newer, for example from your distribution's package manager, [Adoptium](https://adoptium.net/temurin/releases/), [Azul](https://www.azul.com/downloads/) or [Amazon Corretto](https://aws.amazon.com/corretto/)
- [MySQL 8](https://dev.mysql.com/doc/refman/8.4/en/linux-installation.html). MariaDB is **not** supported: the schema uses MySQL-only syntax. Note that on Debian `apt-get install mysql-server` installs MariaDB.
- [imgproxy](https://docs.imgproxy.net/GETTING_STARTED) for serving resized images
- [libvips](https://github.com/libvips/libvips) for processing uploads. Animated GIFs are converted to animated WebP, so the build needs GIF and WebP support, which the builds recommended below have
- [ffmpeg](https://ffmpeg.org/) for video attachments, optional. Uploaded videos are transcoded to 720p H.264 MP4 in the background; without ffmpeg the server refuses video uploads and everything else works
- A web server such as nginx or Caddy in front of everything, terminating TLS

### Install from sources

1. Build the jar with `mvn package -DskipTests=true`. Place `target/smithereen.jar` at `/opt/smithereen/smithereen.jar` and copy the dependencies from `target/lib` next to it:
   ```
   /opt/smithereen
   ├╴ smithereen.jar
   └╴ lib
      ├╴ activation-1.1.jar
      └╴ ...other dependencies
   ```
2. Make libvips available: download a prebuilt one from [sharp-libvips](https://github.com/lovell/sharp-libvips), or build it with [the build script](docker/native/libvips/build.sh) (linux-x64). A distribution package may work, but not all libvips builds include every feature the server needs.
3. Install and configure imgproxy.
4. Create a config file from [the commented example](examples/config.properties). Uploaded files can be stored on the local file system (default) or in an S3-compatible object storage, see below.
5. Create an empty MySQL database with the `utf8mb4` character set and give the configured user full rights on it. The server creates all tables itself on first start from [the schema](schema.sql) bundled in the jar.
6. Install the daemon. On systemd, copy [the service file](examples/smithereen.service) to `/etc/systemd/system`, then run `systemctl daemon-reload` and `service smithereen start`.
7. Create the first account, which becomes the server owner:
   ```
   java -jar /opt/smithereen/smithereen.jar /etc/smithereen/config.properties init_admin
   ```
8. Configure your web server to proxy requests to the server and to imgproxy, and to serve uploaded files. See the examples for [nginx](examples/nginx.conf) and [Caddy](examples/Caddyfile).
9. Log in with that account and configure the rest in the admin panel: server name, sign-up mode, email confirmation, captcha, rules.

### Using Docker

Copy [the Docker config example](examples/config_docker.properties) to the project root as `config.properties` and set your domain. Edit `docker-compose.yml` to add your imgproxy secrets. `docker compose up` then builds the image from this repository and runs the server, MySQL and imgproxy. You still need a web server in front: [nginx](examples/nginx.conf), [Caddy](examples/Caddyfile). Create the first account with `docker compose exec web bash -c ./smithereen-init-admin`.

A prebuilt image is published to the GitHub Container Registry of this repository as `ghcr.io/gritse/tusovochka:latest`, with an additional tag for every commit it was built from and a timestamp tag like `2026.10.25.203850` (Warsaw time) for pinning a particular build. It is built automatically on every push to `master` by the "Build and publish Docker image" workflow, which can also be started by hand in the Actions tab to rebuild the latest commit on `master`. To use it instead of building locally, replace `build: .` with `image: ghcr.io/gritse/tusovochka:latest` in `docker-compose.yml`.

### Upgrading

Stop the services, replace `smithereen.jar`, the libvips libraries and the whole `lib` directory, and start the services again. Never merge an old `lib` directory with a new one, or the JVM class loader will get confused. The first start after an upgrade may take a while because database migrations run then. Only an empty database is initialized from the bundled schema; an existing one is always migrated in place.

#### Upgrading an existing Smithereen installation

Tusovochka can take over a database from upstream Smithereen 1.0.x (schema version 91). The migration to schema version 92 **permanently deletes** every remote user and group and all content they authored, together with every table and column that only served federation. Local users and their content are kept. Back up the database first, this step cannot be undone. After the upgrade, nothing is delivered to the followers your users had on other servers, and those servers keep whatever copies they already hold.

### Members-only server

By default profiles, walls, public posts, groups and photos are visible to anyone on the internet, like on a classic public social network. To restrict everything to registered users, set `private_server=true` in `config.properties` and restart. Anonymous visitors then only get the front page, login, sign-up and password reset, the "about this server" page and the privacy policy; every other page redirects to the login form, and the API refuses calls without an access token.

Uploaded files and resized images are served by your web server and imgproxy, not by the Java server, so this setting does not cover them. Their URLs are unguessable random names, but anyone who already has such a link can open it without logging in.

### Video and audio attachments

Posts, comments and private messages accept videos when [ffmpeg](https://ffmpeg.org/) is installed where the server runs (the Docker image includes it). Uploads are transcoded in the background to H.264 and AAC in MP4, scaled down to 720p, and shown as a poster frame with a "processing" notice until they are ready. The limits and the CPU budget are set in `config.properties`, see the commented keys in [the example](examples/config.properties): `media.video.max_size` (300 MB by default; the web server in front must allow request bodies at least this large), `media.video.max_duration`, `media.video.max_height`, and `media.ffmpeg_threads` with `media.processing_threads`, which both default to 1 so that transcoding never uses more than one core. Audio files are accepted in the same places and transcoded the same way, to AAC in M4A, with `media.audio.max_size` (100 MB) and `media.audio.max_duration` (2 hours). Videos and audio are served as plain files by the web server or the object storage, not through imgproxy. Profile pictures and photo albums take still images only.

### Using S3 object storage

Set `upload.backend=s3` in `config.properties`, then configure the properties for your provider:

- `upload.s3.region`: the region, `us-east-1` by default. Required for AWS; other providers may accept any value.
- `upload.s3.endpoint`: the S3 endpoint, `s3.<region>.amazonaws.com` by default. Required when **not** using AWS.
- `upload.s3.key_id` and `upload.s3.secret_key`: your credentials.
- `upload.s3.bucket`: the bucket name.
- `upload.s3.override_path_style`: when `upload.s3.endpoint` is set, set this to `true` if your provider wants the bucket name in the hostname rather than in the path, like `<bucket>.<endpoint>`.
- `upload.s3.protocol`: `https` by default, can be set to `http`.

These properties control the public URLs that imgproxy and browsers use to read files from the bucket:

- `upload.s3.hostname`: defaults to `s3-<region>.amazonaws.com`. Set it when not using AWS and `upload.s3.alias_host` is not set.
- `upload.s3.alias_host`: use instead of `upload.s3.hostname` to hide the bucket name behind a CDN or reverse proxy. With it set, URLs look like `<protocol>://<alias_host>/<object>`; without it, `<protocol>://<hostname>/<bucket>/<object>`.

The bucket must allow anonymous reads of objects but not directory listing. [The Mastodon documentation](https://docs.joinmastodon.org/admin/optional/object-storage/#minio) explains how to set that up with various providers.

imgproxy also has to be allowed to read from the bucket, with a trailing slash in the URL:

```
IMGPROXY_ALLOWED_SOURCES=local://,https://<your S3 hostname or alias host>/
```

## API

The server exposes a JSON API for third-party applications under `/api/method/<name>` with OAuth authorization. Applications are registered in the "Applications" tab of the user settings. The API is inherited from Smithereen, and [its documentation](https://smithereen.software/docs/api) still applies, except for anything that mentions remote servers or federation.

## Using with AI agents (MCP)

The server is also an [MCP](https://modelcontextprotocol.io) server at `https://<your domain>/mcp`, so an AI agent such as Claude Code or one built on the Claude Agent SDK can act on behalf of a user: read the news feed and notifications, post and comment, like, manage friends and groups, and read and send private messages. The tools are thin wrappers over the JSON API, so the same privacy rules, scopes and rate limits apply. The design is described in [docs/mcp-server-spec.md](docs/mcp-server-spec.md).

To let an agent use your account:

1. Open Settings → Applications → "Access tokens for AI agents" and create a token. Choose a label, how long it stays valid (1 day to 1 year) and what it may do: by default it can read everything and write posts, comments and private messages. The token is shown once; the same page shows the ready-to-paste configuration.
2. Put the token into an environment variable where the agent runs, and point the agent at the endpoint. For Claude Code, this is a `.mcp.json` in the project (or `claude mcp add --transport http tusovochka https://<your domain>/mcp --header "Authorization: Bearer <token>"`):
   ```json
   {
     "mcpServers": {
       "tusovochka": {
         "type": "http",
         "url": "https://<your domain>/mcp",
         "headers": { "Authorization": "Bearer ${TUSOVOCHKA_TOKEN}" }
       }
     }
   }
   ```
3. Revoke the token on the same settings page when it is no longer needed. Changing your password or ending all other sessions revokes it too.

Notes for server owners: the endpoint accepts the token only in the `Authorization` header, refuses requests with a foreign `Origin`, never streams (no SSE, no sessions), and limits each token to 3 requests per second and 60 writes per hour. On a members-only server (`private_server=true`) it works the same, because a token always identifies a member. Set `mcp.enabled=false` in `config.properties` to turn it off. The agent-facing tools never include administration or moderation.

To have new messages, comments, mentions and invites pushed into a running Claude Code session as they happen, add the channel bridge from [channel/](channel/README.md) next to the MCP server. It reads the event stream at `wss://<your domain>/mcp/events` with the same token, which needs the `notifications` scope.

## Contributing

Issues and pull requests are welcome in this repository. Please keep the scope in mind: this fork will not add federation back, and it keeps the upstream philosophy of a network you use to stay in touch with people you know, without hashtags, public feeds or global search.

Translations live in `src/main/resources/langs`. Edit the JSON files directly; the upstream Crowdin project belongs to Smithereen and is not used here.

## License

This is free and unencumbered software released into the public domain. See [UNLICENSE](UNLICENSE).
