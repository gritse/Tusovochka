// Service worker for browser push notifications. It lives at the site root so that its scope covers every page.
// The server sends {title, body, url} as the encrypted payload, see WebPushController.

self.addEventListener("push", (event) => {
	let data = {};
	if (event.data) {
		try {
			data = event.data.json();
		} catch (e) {
			data = {body: event.data.text()};
		}
	}
	event.waitUntil(self.registration.showNotification(data.title || "Tusovochka", {
		body: data.body || "",
		icon: "/res/icon-192.png",
		data: {url: data.url || "/"}
	}));
});

self.addEventListener("notificationclick", (event) => {
	event.notification.close();
	const url = new URL(event.notification.data.url || "/", self.location.origin).href;
	event.waitUntil(self.clients.matchAll({type: "window", includeUncontrolled: true}).then((windows) => {
		for (const w of windows) {
			if (w.url === url && "focus" in w)
				return w.focus();
		}
		return self.clients.openWindow(url);
	}));
});
