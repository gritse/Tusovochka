// Browser end-to-end run for reposts: who may share what, what the repost shows, and what each viewer sees when the
// original stops being public. See TESTS.md section 11 for the cases it covers.
//
// Usage: NODE_PATH=<dir with playwright> node dev/reposts.js
// Environment: BASE_URL (default http://localhost:4567, must match the server's domain), DB_CONTAINER (default smdb, the MySQL container from
//              TESTS.md, used to check stored rows and to make a post friends-only, which the web UI can't do).
// Needs the four seeded users (dev/Seeder.java). Each run creates posts and groups, so use a throwaway database.
const {chromium, devices} = require('playwright');
const {execFileSync} = require('child_process');

const base = process.env.BASE_URL || 'http://localhost:4567'; // the dev config's domain: links and AJAX use it
const dbContainer = process.env.DB_CONTAINER || 'smdb';

let failures = 0;
function check(cond, msg) {
	console.log((cond ? 'PASS ' : 'FAIL ') + msg);
	if (!cond)
		failures++;
}

function sql(query) {
	return execFileSync('docker', ['exec', dbContainer, 'mysql', '-uroot', '-pmysql', '-N', '-B', 'smithereen', '-e', query], {encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']}).trim();
}

function repostCount(postID) {
	return parseInt(sql(`SELECT COUNT(*) FROM wall_posts WHERE repost_of=${postID}`));
}

function newestPostBy(authorID) {
	return parseInt(sql(`SELECT MAX(id) FROM wall_posts WHERE author_id=${authorID}`));
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

let browser;
async function session(login, mobile) {
	const context = await browser.newContext(mobile ? {...devices['iPhone 13']} : {viewport: {width: 1280, height: 900}});
	const page = await context.newPage();
	page.on('pageerror', e => {
		console.log('PAGE ERROR (' + (login || 'guest') + ') ' + e.message);
		failures++;
	});
	if (!login)
		return {page};
	await sleep(1200); // login is rate-limited to one attempt per second
	await page.goto(base + '/account/login');
	await page.fill('input[name=username]', login);
	await page.fill('input[name=password]', 'password');
	await Promise.all([page.waitForURL(u => !/login/.test(u.pathname)), page.click('form button, form input[type=submit]')]);
	const uid = await page.evaluate(() => userConfig.uid);
	return {page, uid};
}

// POSTs a form to the server the way the site's own scripts do, with the session's CSRF token
async function post(page, path, params) {
	return await page.evaluate(async ({path, params}) => {
		const r = await fetch(path + (path.includes('?') ? '&' : '?') + '_ajax=1&csrf=' + userConfig.csrf, {method: 'POST', body: new URLSearchParams(params)});
		return {status: r.status, body: await r.text()};
	}, {path, params});
}

async function get(page, path) {
	return await page.evaluate(async path => {
		const r = await fetch(path + (path.includes('?') ? '&' : '?') + '_ajax=1');
		return {status: r.status, body: await r.text()};
	}, path);
}

async function createPost(s, wallPath, text) {
	const r = await post(s.page, wallPath + '/createWallPost', {text});
	check(r.status == 200, `${wallPath}: post "${text}" created (${r.status})`);
	return newestPostBy(s.uid);
}

// Tries to repost by sending the share form directly, bypassing the button. Returns the HTTP status.
async function forgeRepost(s, postID, text) {
	return (await post(s.page, '/users/' + s.uid + '/createWallPost', {text: text || '', repost: postID})).status;
}

async function createGroup(s, name) {
	await s.page.goto(base + '/my/groups/create');
	await s.page.fill('input[name=name]', name);
	await Promise.all([s.page.waitForNavigation(), s.page.click('form input[type=submit], form button[type=submit]')]);
	const id = parseInt(sql(`SELECT MAX(id) FROM \`groups\``));
	check(id > 0, `group "${name}" created: ${id}`);
	return id;
}

async function setGroupAccess(s, groupID, access) {
	await s.page.goto(base + '/groups/' + groupID + '/edit');
	await Promise.all([s.page.waitForNavigation(), s.page.evaluate(access => {
		const form = document.querySelector('form[action$="saveGeneral"]');
		form.elements.access.value = access;
		form.submit();
	}, access)]);
	check(sql(`SELECT access_type FROM \`groups\` WHERE id=${groupID}`) == {OPEN: '0', CLOSED: '1', PRIVATE: '2'}[access], `group ${groupID} is now ${access}`);
}

async function setOthersPostsVisibility(s, rule) {
	const r = await post(s.page, '/settings/privacy', {WALL_OTHERS_POSTS: JSON.stringify({r: rule, au: [], xu: [], al: [], xl: []})});
	check(r.status == 200, `user ${s.uid}: others' posts on my wall visible to "${rule}"`);
}

async function hasShareButton(s, postID) {
	await s.page.goto(base + '/posts/' + postID);
	return (await s.page.$(`a.share[href$="/posts/${postID}/share"]`)) != null;
}

// Shares a post through the real UI: the share button, the box, the comment field and the submit button
async function shareViaButton(s, postID, comment) {
	const before = repostCount(postID);
	await s.page.goto(base + '/posts/' + postID);
	await s.page.click(`a.share[href$="/posts/${postID}/share"]`);
	await s.page.waitForSelector('#postFormText_repost', {state: 'visible'});
	await s.page.fill('#postFormText_repost', comment);
	await s.page.click('#postFormSubmit_repost');
	for (let i = 0; i < 50 && repostCount(postID) == before; i++)
		await sleep(100);
	await s.page.waitForSelector('#postFormText_repost', {state: 'detached', timeout: 5000}).catch(() => {});
	check(repostCount(postID) == before + 1, `user ${s.uid} shared post ${postID} with the share button`);
	return newestPostBy(s.uid);
}

// What a viewer sees of one repost on a wall page: its header and content, or the placeholder
async function viewRepost(s, wallPath, repostID) {
	const r = await s.page.goto(base + wallPath);
	const html = await s.page.content();
	check(r.status() == 200 && !/Internal Server Error|Exception/.test(html), `${s.uid || 'guest'} opens ${wallPath} without errors`);
	return await s.page.evaluate(id => {
		const el = document.querySelector(`[id^="post${id}_"]`) || document.querySelector(`#post${id}`);
		if (!el)
			return null;
		const header = el.querySelector('.repostHeader');
		return {
			header: header ? header.innerText.replace(/\s+/g, ' ').trim() : null,
			text: el.innerText,
			unavailable: el.querySelector('.repostUnavailable') != null,
		};
	}, repostID);
}

(async () => {
	browser = await chromium.launch();
	const admin = await session('admin'), yulia = await session('yulia'), maria = await session('maria'), dmitry = await session('dmitry');
	const guest = await session(null);
	const adminMobile = await session('admin', true);
	const adminWall = '/users/' + admin.uid;

	console.log('--- 1. a post on its author\'s own wall');
	const own = await createPost(yulia, '/users/' + yulia.uid, 'Yulia own post');
	check(await hasShareButton(admin, own), 'share button on a public own-wall post');
	const repostOwn = await shareViaButton(admin, own, 'Admin comment on Yulia');
	let v = await viewRepost(admin, adminWall, repostOwn);
	check(v && !v.unavailable && /Юлия Куминова/.test(v.header) && /Yulia own post/.test(v.text) && /Admin comment on Yulia/.test(v.text), 'repost shows the author, the original and the comment: ' + JSON.stringify(v && v.header));
	check(v && !/▸|→/.test(v.header), 'no wall owner in the header of an own-wall post');

	console.log('--- 2. a post in an open group');
	const openGroup = await createGroup(maria, 'Open repost group');
	const groupPost = await createPost(maria, '/groups/' + openGroup, 'Open group post');
	check(await hasShareButton(admin, groupPost), 'share button on an open group post (was missing before)');
	const repostGroup = await shareViaButton(admin, groupPost, 'Admin shares the group post');
	v = await viewRepost(admin, adminWall, repostGroup);
	check(v && !v.unavailable && /Мария Соколова/.test(v.header) && /Open repost group/.test(v.header) && /Open group post/.test(v.text), 'header names the author and the group: ' + JSON.stringify(v && v.header));
	v = await viewRepost(guest, adminWall, repostGroup);
	check(v && /Open group post/.test(v.text) && !v.unavailable, 'a logged-out visitor sees the shared group post');
	v = await viewRepost(adminMobile, adminWall, repostGroup);
	check(v && !v.unavailable && /Open repost group/.test(v.header) && /Open group post/.test(v.text), 'mobile layout shows the group in the header: ' + JSON.stringify(v && v.header));

	console.log('--- 3. a post someone wrote on another user\'s wall');
	const wallToWall = await createPost(dmitry, '/users/' + yulia.uid, 'Dmitry on Yulia wall');
	check(await hasShareButton(admin, wallToWall), 'share button on a wall-to-wall post when everyone sees others\' posts (was missing before)');
	const repostW2W = await shareViaButton(admin, wallToWall, 'Admin shares the wall-to-wall post');
	v = await viewRepost(admin, adminWall, repostW2W);
	check(v && !v.unavailable && /Дмитрий Орлов/.test(v.header) && /Юлия Куминова/.test(v.header) && /Dmitry on Yulia wall/.test(v.text), 'header names the author and the wall owner: ' + JSON.stringify(v && v.header));

	console.log('--- 4. the wall owner hides others\' posts');
	await setOthersPostsVisibility(yulia, 'n');
	check(!(await hasShareButton(yulia, wallToWall)), 'no share button on that post, even for the wall owner');
	let r = await get(maria.page, '/posts/' + wallToWall + '/share');
	check(!/postFormText_repost/.test(r.body), 'the share form refuses to open: ' + r.status);
	let before = repostCount(wallToWall);
	r = {status: await forgeRepost(maria, wallToWall, 'forged')};
	check(repostCount(wallToWall) == before, 'a forged repost request is refused: ' + r.status);
	v = await viewRepost(admin, adminWall, repostW2W);
	check(v && v.unavailable && !/Dmitry on Yulia wall/.test(v.text) && /Admin shares the wall-to-wall post/.test(v.text), 'the existing repost now shows the placeholder, the comment stays');
	v = await viewRepost(guest, adminWall, repostW2W);
	check(v && v.unavailable && !/Dmitry on Yulia wall/.test(v.text), 'logged out: placeholder too');
	v = await viewRepost(yulia, adminWall, repostW2W);
	check(v && !v.unavailable && /Dmitry on Yulia wall/.test(v.text), 'the wall owner still sees the content');
	await setOthersPostsVisibility(yulia, 'e');
	v = await viewRepost(admin, adminWall, repostW2W);
	check(v && !v.unavailable && /Dmitry on Yulia wall/.test(v.text), 'back to "everyone": the content returns');

	console.log('--- 5. the group becomes closed after a post was shared');
	const closingGroup = await createGroup(maria, 'Soon closed group');
	const closingPost = await createPost(maria, '/groups/' + closingGroup, 'Post in a group that closes');
	const repostClosing = await shareViaButton(admin, closingPost, 'Admin shares before it closes');
	await setGroupAccess(maria, closingGroup, 'CLOSED');
	check(!(await hasShareButton(maria, closingPost)), 'no share button in a closed group, even for a member');
	before = repostCount(closingPost);
	r = {status: await forgeRepost(maria, closingPost, '')};
	check(repostCount(closingPost) == before, 'a forged repost of a closed group post is refused: ' + r.status);
	v = await viewRepost(admin, adminWall, repostClosing);
	check(v && v.unavailable && !/Post in a group that closes/.test(v.text), 'non-member: the repost shows the placeholder');
	v = await viewRepost(guest, adminWall, repostClosing);
	check(v && v.unavailable && !/Post in a group that closes/.test(v.text), 'logged out: placeholder');
	v = await viewRepost(adminMobile, adminWall, repostClosing);
	check(v && v.unavailable && !/Post in a group that closes/.test(v.text), 'mobile non-member: placeholder');
	v = await viewRepost(maria, adminWall, repostClosing);
	check(v && !v.unavailable && /Post in a group that closes/.test(v.text), 'a member still sees the content');
	r = await get(guest.page, '/posts/' + repostClosing);
	check(!/Post in a group that closes/.test(r.body), 'the repost\'s own page doesn\'t leak it either');
	r = await get(admin.page, '/posts/' + closingPost + '/reposts');
	check(!/Post in a group that closes/.test(r.body), 'nor does the list of reposts');

	console.log('--- 6. a non-public post');
	const friendsOnly = await createPost(yulia, '/users/' + yulia.uid, 'Yulia friends only');
	sql(`UPDATE wall_posts SET privacy=3 WHERE id=${friendsOnly}`); // FRIENDS_ONLY; the web UI can't set it
	check(!(await hasShareButton(yulia, friendsOnly)), 'no share button on a friends-only post, even for its author');
	before = repostCount(friendsOnly);
	r = {status: await forgeRepost(yulia, friendsOnly, 'forged')};
	check(repostCount(friendsOnly) == before, 'a forged repost is refused: ' + r.status);

	console.log('--- 7. sharing a repost without a comment shares the original');
	r = {status: await forgeRepost(maria, own, '')};
	const emptyRepost = newestPostBy(maria.uid);
	check(sql(`SELECT repost_of FROM wall_posts WHERE id=${emptyRepost}`) == String(own), 'maria shared yulia\'s post without a comment: ' + r.status);
	check(await hasShareButton(dmitry, emptyRepost), 'share button on that repost');
	r = {status: await forgeRepost(dmitry, emptyRepost, '')};
	check(sql(`SELECT repost_of FROM wall_posts WHERE id=${newestPostBy(dmitry.uid)}`) == String(own), 'dmitry\'s repost points at yulia\'s original, not at maria\'s repost');
	r = {status: await forgeRepost(dmitry, repostOwn, '')};
	check(sql(`SELECT repost_of FROM wall_posts WHERE id=${newestPostBy(dmitry.uid)}`) == String(repostOwn), 'a repost with a comment is shared as itself');

	console.log('--- 8. reposts only go to the reposter\'s own wall');
	// The first group has ID 1, the same as admin's user ID, which the old check confused
	console.log('open group ID: ' + openGroup + ', admin user ID: ' + admin.uid);
	let postCount = parseInt(sql('SELECT COUNT(*) FROM wall_posts'));
	r = await post(admin.page, '/groups/' + openGroup + '/createWallPost', {text: 'Trying to repost onto a group wall', repost: groupPost});
	check(r.status == 403 && parseInt(sql('SELECT COUNT(*) FROM wall_posts')) == postCount, 'a repost aimed at a group wall is refused and nothing is posted: ' + r.status);
	r = await post(admin.page, '/users/' + yulia.uid + '/createWallPost', {text: 'Trying to repost onto yulia\'s wall', repost: groupPost});
	check(r.status == 403 && parseInt(sql('SELECT COUNT(*) FROM wall_posts')) == postCount, 'so is one aimed at someone else\'s wall: ' + r.status);
	r = await post(admin.page, '/users/' + admin.uid + '/createWallPost', {text: 'A repost can\'t be a comment', repost: groupPost, replyTo: groupPost});
	check(r.status == 403 && parseInt(sql('SELECT COUNT(*) FROM wall_posts')) == postCount, 'and one sent as a comment: ' + r.status);

	console.log('--- 9. comments');
	r = await post(admin.page, '/users/' + maria.uid + '/createWallPost', {text: 'Admin comments on the group post', replyTo: groupPost});
	const groupComment = newestPostBy(admin.uid);
	check(sql(`SELECT reply_key IS NOT NULL FROM wall_posts WHERE id=${groupComment}`) == '1', 'admin commented on the open group post: ' + r.status);
	before = repostCount(groupComment);
	r = {status: await forgeRepost(dmitry, groupComment, '')};
	check(repostCount(groupComment) == before + 1, 'a comment under an open group post can be shared: ' + r.status);
	r = await post(maria.page, '/users/' + maria.uid + '/createWallPost', {text: 'Member comments in the closed group', replyTo: closingPost});
	const closedComment = newestPostBy(maria.uid);
	before = repostCount(closedComment);
	r = {status: await forgeRepost(maria, closedComment, '')};
	check(repostCount(closedComment) == before, 'a comment under a closed group post can\'t: ' + r.status);

	console.log('--- 10. the original is deleted');
	r = await post(yulia.page, '/posts/' + own + '/delete', {});
	check(sql(`SELECT COUNT(*) FROM wall_posts WHERE id=${own}`) == '0', 'yulia deleted her post: ' + r.status);
	v = await viewRepost(admin, adminWall, repostOwn);
	check(v && v.unavailable && /Admin comment on Yulia/.test(v.text), 'the repost shows the placeholder and keeps its comment');
	v = await viewRepost(maria, '/users/' + maria.uid, emptyRepost);
	check(v && v.unavailable, 'a repost without a comment shows the placeholder');
	check(!(await hasShareButton(dmitry, emptyRepost)), 'and has no share button, there is nothing to share');

	console.log('--- 11. the group is banned after a post was shared');
	const banGroup = await createGroup(maria, 'Soon banned group');
	const banPost = await createPost(maria, '/groups/' + banGroup, 'Post in a group that gets banned');
	const repostBan = await shareViaButton(dmitry, banPost, 'Dmitry shares before the ban');
	r = await post(admin.page, '/groups/' + banGroup + '/ban', {status: 'SUSPENDED', message: 'test'});
	check(sql(`SELECT ban_status FROM \`groups\` WHERE id=${banGroup}`) == '1', 'admin suspended the group: ' + r.status);
	for (const [s, label] of [[maria, 'the group\'s own member'], [guest, 'logged out'], [admin, 'staff']]) {
		v = await viewRepost(s, '/users/' + dmitry.uid, repostBan);
		check(v && v.unavailable && !/Post in a group that gets banned/.test(v.text) && /Dmitry shares before the ban/.test(v.text), label + ': the repost shows the placeholder');
	}
	before = repostCount(banPost);
	r = {status: await forgeRepost(yulia, banPost, '')};
	check(repostCount(banPost) == before, 'a new repost of it is refused: ' + r.status);

	console.log('--- 12. other pages that show reposts');
	for (const [s, path] of [[yulia, '/my/notifications'], [maria, '/my/notifications'], [admin, '/feed'], [admin, '/posts/' + repostGroup], [admin, '/posts/' + groupPost + '/reposts'], [adminMobile, '/posts/' + repostGroup], [adminMobile, '/feed'], [maria, '/groups/' + openGroup], [adminMobile, '/groups/' + openGroup], [adminMobile, '/users/' + yulia.uid]]) {
		const resp = await s.page.goto(base + path);
		check(resp.status() == 200 && !/Internal Server Error|Exception/.test(await s.page.content()), `${s.uid} opens ${path} without errors`);
	}
	// The profile's list of groups used to share its template variable with the wall's groups
	const mariaMobile = await session('maria', true);
	await admin.page.goto(base + '/users/' + maria.uid);
	check(/Open repost group/.test(await admin.page.content()), 'desktop: maria\'s profile still lists her groups');
	await adminMobile.page.goto(base + '/users/' + maria.uid);
	check(await adminMobile.page.$(`a[href$="/users/${maria.uid}/groups"] .avatarGrid`) != null, 'mobile: maria\'s profile still has the groups block (avatars only)');
	await mariaMobile.page.goto(base + '/groups/' + openGroup);
	check(!/Exception/.test(await mariaMobile.page.content()), 'mobile group page of its admin renders');
	await maria.page.goto(base + '/my/notifications');
	check(/Admin|Иван/.test(await maria.page.content()), 'maria\'s notifications mention admin\'s reposts of her posts');

	await browser.close();
	console.log(failures == 0 ? 'ALL PASSED' : failures + ' FAILED');
	process.exit(failures == 0 ? 0 : 1);
})().catch(e => {
	console.error(e);
	process.exit(1);
});
