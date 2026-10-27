# Testing cheatsheet

How to run the automated tests, spin up a local instance with sample data, and check a feature in a real browser.
Everything below was verified on Linux with JDK 21, Maven, Docker and Node 22.

## 1. Automated tests

```sh
mvn test                                   # everything
mvn -Dtest=TemplateSyntaxTest test         # parses every .twig template (fast, catches template typos)
mvn -q -DskipTests compile                 # compile only, also builds CSS and TypeScript
```

`TemplateSyntaxTest` only parses templates. It does not render them, so a wrong variable name still passes.
For that you need the browser check below.

### "couldn't find matching tag" / "bad value for --module-version"

The build takes the project version from the nearest git tag. A shallow clone has no tags, so create a
throwaway local one (do not push it):

```sh
git tag 0.0.0-local          # must look like a version number, "v0.0.0" is rejected by javac
```

## 2. Spin up a local instance

### Database

```sh
docker run -d --name smdb -e MYSQL_ROOT_PASSWORD=mysql -e MYSQL_DATABASE=smithereen \
  -p 127.0.0.1:3306:3306 mysql:8
```

If `docker ps` says the daemon is not running, start it with `dockerd &` (root) and retry.
The server creates the schema itself on first start, there is nothing to import.

### Config

```sh
cp dev/config.dev.properties config.properties      # git-ignored
mkdir -p /tmp/tusovochka-uploads                     # or change upload.path in the file
```

The dev config points at the database above, uses plain `http://localhost:4567` and dummy imgproxy keys.
Images are not served without imgproxy and nginx, so expect "?" avatars. That is fine for UI checks.

### Build and run

```sh
mvn -q -DskipTests -Dmaven.test.skip=true package    # produces target/smithereen.jar and target/lib
java -Djna.library.path=/lib/x86_64-linux-gnu \
  --module-path target/smithereen.jar:target/lib \
  -m smithereen.server/smithereen.SmithereenApplication config.properties
```

`jna.library.path` must point at a directory with `libvips.so.42` (`ldconfig -p | grep vips`).
On Debian/Ubuntu: `apt-get install libvips42`. The Docker image bundles its own copy.

The server is ready when `curl http://127.0.0.1:4567/healthz` returns 200.
Logs go to stdout.

### Sample users and messages

`init_admin` needs an interactive terminal, so for a scripted setup use the seeder in `dev/`. It creates
four accounts and a dozen private messages spread over two days (one dialog with unread messages, one read,
one where the last message is yours). The seeder is compiled against the built classes and patched into
the server module, because the module does not export its packages:

```sh
mkdir -p target/dev
javac -cp "target/classes:target/lib/*" -d target/dev dev/Seeder.java
java --module-path target/smithereen.jar:target/lib --patch-module smithereen.server=target/dev \
  -m smithereen.server/smithereen.Seeder config.properties
```

| Login (e-mail or username) | Password | Role |
|---|---|---|
| admin@example.com / `admin` | password | owner, has the admin menu |
| yulia@example.com / `yulia` | password | sends the unread messages |
| maria@example.com / `maria` | password | |
| dmitry@example.com / `dmitry` | password | |

The seeder refuses to run on a database that already has users. To start over:

```sh
docker rm -f smdb && docker run ... (same command as above)
```

### Alternative: the Docker image

`docker compose up` builds the full image (several minutes) and runs it with MySQL and imgproxy.
It needs a `config.properties` and `/var/www/smithereen/s` on the host, see the README. Create the first
user with `docker compose exec web smithereen-init-admin`. For quick UI checks the local run above is faster
because `mvn package` is incremental.

## 3. Check the MCP endpoint

Log in as any user, create a token in Settings → Applications → "Access tokens for AI agents", then run the smoke
test against the running server:

```sh
dev/mcp-smoke.sh <token>                    # transport checks, then a post, a comment and their deletion
```

With Claude Code installed, `claude mcp add --transport http tusovochka http://127.0.0.1:4567/mcp --header "Authorization: Bearer <token>"`
followed by `claude mcp list` shows whether the real client connects, and `claude -p "..."` with
`--allowedTools "mcp__tusovochka__*"` runs an agent against it.

### The Claude Code channel (`/mcp/events`)

Run `npm install` in `channel/`, put the `.mcp.json` from `channel/README.md` into a scratch directory (the
"from a checkout" variant, so the code under test runs, with `http://127.0.0.1:4567` as the URL and a token for
`admin`), and start an interactive session there:
`TUSOVOCHKA_TOKEN=<token> claude --dangerously-load-development-channels server:tusovochka-events`. Channels do not
work with `claude -p`, so to script it run Claude inside `tmux` and drive it with `tmux send-keys` /
`tmux capture-pane`. Then, with a token for `yulia`, call `send_message` to user 1 or `create_comment` on one of
admin's posts through `/mcp` (the `curl` in `dev/mcp-smoke.sh` shows the request format): the session prints
`← tusovochka-events: …` and reacts. Without Claude, `node -e` with `new WebSocket(url, {headers: {Authorization:
'Bearer …'}})` prints the raw events.

## 4. Check a feature in the browser

Open `http://localhost:4567`, log in as one of the users above. Useful pages:

| Page | URL |
|---|---|
| Dialogs (one row per person) | `/my/mail` |
| Sent messages | `/my/mail/outbox` |
| Chat with user 2 | `/my/mail/with/2` |
| Single message | `/my/mail/messages/<id>` (linked from the timestamp in the chat) |
| Compose | `/my/mail/compose` |
| Profile | `/<username>`, e.g. `/yulia` |
| Admin | `/settings/admin` |
| Health | `/healthz` |

Tips:

- Login is rate-limited to one attempt per second and 100 per hour per IP. A script that logs in several
  times in a row needs a pause between attempts, otherwise it gets the login page back.
- The mobile layout is chosen by user agent, not by viewport width. Use the device toolbar in the browser's
  dev tools (iPhone preset) or any UA containing "Mobile".
- To test both sides of a conversation, log in as two users in two browser profiles or a private window.
  New messages arrive live in the chat view through the notifier websocket.
- The "language" link in the footer switches the UI language and stores it in the account, so Russian
  strings can be checked without changing the browser's `Accept-Language`.
- Templates are packed into the jar at build time, so after editing a `.twig`, `.scss` or `.ts` file run
  `mvn -q -DskipTests -Dmaven.test.skip=true package` and restart the server.
- `src/main/web/live_recompile.sh` watches the SCSS and TypeScript and writes into `target/classes`, which
  only helps when the server runs from `target/classes` (an IDE run), not from the jar.

### Headless screenshots

`dev/screenshot.js` logs in and screenshots pages on desktop and mobile with Playwright. It expects
`playwright` to be resolvable by Node (a global install, or `npm i playwright` in a scratch directory and
`NODE_PATH` pointing at its `node_modules`):

```sh
NODE_PATH=/path/to/node_modules node dev/screenshot.js screenshots /my/mail /my/mail/with/2
```

Environment variables: `BASE_URL` (default `http://127.0.0.1:4567`), `LOGIN`, `PASSWORD`
(default admin@example.com / password). Output lands in the directory given as the first argument.

## 4. Checklist for the messages feature

1. `/my/mail` shows one row per person, newest dialog first, with the latest message in either direction.
   Your own messages are prefixed with "You:", unread dialogs are highlighted and carry a count badge.
2. Clicking a row opens `/my/mail/with/<id>`. Coming back, the badge is gone and the sidebar counter dropped.
3. Sending from the chat appends the message in place. In another session as the recipient, the message
   appears without a reload.
4. "Show earlier messages" at the top of a long chat prepends the previous page and keeps the scroll position.
5. `/my/mail/outbox` still lists individual sent messages with Delete.
6. Send a message from `/my/mail/compose`: you land on the dialog list with a "Your message was sent" notice.

## 5. Checklist for the friends feed ("My News")

The seeder does not create friendships or posts, so set them up first: log in as `admin` and send a friend
request to `yulia` from her profile, then log in as `yulia` and accept it from `/my/incomingFriendRequests`.

1. Yulia posts on her own wall: the post appears at the top of admin's `/feed`.
2. Dmitry (not a friend of admin) posts on Yulia's wall: the post appears in admin's feed with
   "dmitry → yulia" (author, arrow, wall owner) in the header and a "Wall-to-wall" link. It also appears in yulia's own feed.
3. Yulia sets "Who can see others' posts on my wall" to "Only me" at `/settings/privacy`: Dmitry's post
   disappears from admin's feed, Yulia's own post stays. Set it back to "Everyone" and it returns.
4. Yulia deletes Dmitry's post from her wall: it disappears from admin's feed.
5. Posts on friends' walls made before the upgrade are not in the feed: only posts created after it are recorded.

## 6. Checklist for animated images (GIF)

Any animated GIF works, or make one with Python: `pip install pillow`, then
`python3 -c "from PIL import Image; f=[Image.new('RGB',(200,200),(i*40,0,255-i*40)) for i in range(6)]; f[0].save('anim.gif',save_all=True,append_images=f[1:],duration=200,loop=0)"`.

1. Attach `anim.gif` to a wall post: the preview in the composer moves, the attachment in the published post moves,
   and so does the image in the photo viewer when you click it. The same works for comments and private messages.
2. Upload it to a photo album: the thumbnail in the album moves and the photo has no rotate buttons, neither in the
   album editor nor in the photo viewer. The viewer's "open original" link points at the stored `.webp` file.
3. Upload it as a profile picture (`/settings` → upload new photo): the server refuses it with "Animated images can't be
   used here". A still GIF, JPEG or PNG is accepted as before. The same refusal applies to group logos and app logos.
4. A still GIF attached to a post is handled exactly like a JPEG (served through imgproxy in all sizes).

## 7. Media regression script

`dev/regression.js` runs the media checks from sections 6 and 8 in a browser: photos, animated GIFs, videos and audio
as post and message attachments, albums, avatars, group logos and app logos. It generates its own test files
with ffmpeg, so ffmpeg must be on the PATH, and logs in as `admin` by default.

```sh
NODE_PATH=/path/to/node_modules UPLOAD_PATH=/tmp/tusovochka-uploads node dev/regression.js
```

`UPLOAD_PATH` is optional; when set, the script also checks that the stored files exist and the spool
directory is empty. Each run creates posts, an album, a group and an app, so run it on a throwaway database.
The unit tests in `src/test/java/smithereen/media` cover the processors themselves and are skipped when
libvips or ffmpeg can't be found; `mvn test` prints them as run, not skipped, when both are installed.

## 8. Checklist for video and audio attachments

Needs ffmpeg installed where the server runs (`ffmpeg -version`). The server logs "ffmpeg found" on the first
page load; without it, `userConfig.uploads.videoEnabled` is false and the composer accepts images only.

1. Attach an MP4 or WebM to a wall post. The composer shows the poster frame with a play mark; after publishing,
   the post shows the poster with "Processing video…", which turns into a player within a few seconds without
   reloading (through the websocket, or the status poll on pages the websocket doesn't reach).
2. The player plays, seeks and has sound. The stored file is a 720p (or smaller) MP4 under the upload path;
   the `spool` directory under it is empty once processing is done.
3. The same works in comments and private messages. Albums and profile pictures refuse videos with a message.
4. Restart the server while a video is processing: the job resumes and finishes after the restart.
5. Upload a file that is not a video with a `.mp4` name: it is refused with the "damaged or unsupported" message.
6. Delete the post: an hour later the abandoned-files job removes the MP4, and the poster on the following pass.
7. Attach an MP3 or WAV: the composer shows its title (from the tags, or the file name), the post lists it under the
   photos with title, artist and duration and "Processing audio…", which becomes a player the same way. Albums and
   profile pictures refuse audio.

## 9. Checklist for browser push notifications (test page)

Push needs HTTPS on a real domain; `localhost` also works in desktop browsers. The section is at the bottom of
`/settings/notifications`.

1. On an Android phone (Chrome or Firefox), open the site, press "Turn on" in "Push notifications (test)" and allow
   notifications. The status changes to "This device receives push notifications" and the phone appears under Devices.
2. On an iPhone or iPad (iOS 16.4+), Safari shows a hint instead of the button. Add the site to the Home Screen
   (Share → Add to Home Screen), open it from the new icon, log in, and turn notifications on there.
3. On a computer, press "Send a test notification". The box lists every device with "sent" or the push service's
   error; the notification arrives on the phones even when the site is closed. Tapping it opens the notifications page.
4. "Turn off" on a device removes it from the list. A device whose subscription expired (the push service answers
   404 or 410) is removed when a test is sent to it.

Headless checks with Playwright need `channel: 'chromium'` and `launchPersistentContext`: the default headless shell
reports notifications as denied, and incognito contexts have no Push API. The subscription and the send work there,
but the notification itself is not delivered when the sandbox can't reach Google's push connection.

## 10. Checklist for push notification events

Push needs at least one device turned on (section 9) for the receiving user. The server logs every push at debug
level with its title and body when started with `-Dorg.slf4j.simpleLogger.log.smithereen.controllers.WebPushController=debug`,
which is the quickest way to check the wording without a phone. Make `admin` and `yulia` friends first (the seeder
does not), either through the UI or with
`INSERT INTO followings (follower_id, followee_id, mutual, accepted) VALUES (1,2,1,1),(2,1,1,1)`.

1. "Push notifications" at the bottom of `/settings/notifications` has the device controls, then four groups of
   events: Notifications, Friends, Groups, Comments. Untick "Likes", save, reload: it stays unticked, the rest stays
   as it was. The "Send a test notification" button is behind the "Debug" spoiler below.
2. Everything the site notifies about pushes like a messaging app, with the person as the title and what they wrote
   or did as the body: yulia writes on admin's wall, comments on admin's post, replies to admin's comment, sends a
   message (the text only when "Show message text" is on, otherwise "sent you a message"), likes admin's post
   ("likes your post …", nothing while "Likes" is off).
3. Yulia posts on her own wall: admin gets a push titled with her name and the first words of the post. Posts by
   others on her wall come as "posted on the wall of …", and are not sent to friends her "who can see others'
   posts on my wall" setting excludes.
4. Admin and yulia both comment on a post, then maria comments on it: admin gets maria's name and her comment, while
   yulia, as the post author, gets it as a regular comment notification and not a second push.
5. Admin joins a group yulia created, yulia posts and starts a topic there: one push per event, titled with the
   group's name. The topic is news in both the friends and the groups feed but arrives once.
6. Upload several photos to an album at once: a single "added a photo to the album …" push, not one per photo.
7. Turn "Friends → Posts" off (hovering a name shows what it covers): step 3 pushes nothing; the groups and comments pushes still arrive.
8. Nobody is told about their own doing: posting in a group one is a member of, or on a friend's wall, pushes nothing
   to the author. Deleting anything pushes nothing to anyone.

