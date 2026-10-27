package smithereen;

import smithereen.controllers.AppsController;
import smithereen.media.MediaProcessingQueue;
import smithereen.controllers.BoardController;
import smithereen.controllers.BookmarksController;
import smithereen.controllers.CommentsController;
import smithereen.controllers.FriendsController;
import smithereen.controllers.GroupsController;
import smithereen.controllers.MailController;
import smithereen.controllers.ModerationController;
import smithereen.controllers.NewsfeedController;
import smithereen.controllers.NotificationsController;
import smithereen.controllers.ObjectLinkResolver;
import smithereen.controllers.PhotosController;
import smithereen.controllers.PrivacyController;
import smithereen.controllers.SearchController;
import smithereen.controllers.StatsController;
import smithereen.controllers.UserDataExportWorker;
import smithereen.controllers.UserInteractionsController;
import smithereen.controllers.UsersController;
import smithereen.controllers.WallController;
import smithereen.controllers.WebPushController;

public class ApplicationContext{
	private final WallController wallController;
	private final GroupsController groupsController;
	private final UsersController usersController;
	private final UserInteractionsController userInteractionsController;
	private final FriendsController friendsController;
	private final ObjectLinkResolver objectLinkResolver;
	private final PrivacyController privacyController;
	private final NewsfeedController newsfeedController;
	private final NotificationsController notificationsController;
	private final ModerationController moderationController;
	private final StatsController statsController;
	private final MailController mailController;
	private final SearchController searchController;
	private final BookmarksController bookmarksController;
	private final PhotosController photosController;
	private final CommentsController commentsController;
	private final BoardController boardController;
	private final UserDataExportWorker userDataExportWorker;
	private final AppsController appsController;
	private final MediaProcessingQueue mediaProcessingQueue;
	private final WebPushController webPushController;

	public ApplicationContext(){
		wallController=new WallController(this);
		groupsController=new GroupsController(this);
		usersController=new UsersController(this);
		userInteractionsController=new UserInteractionsController(this);
		friendsController=new FriendsController(this);
		objectLinkResolver=new ObjectLinkResolver(this);
		privacyController=new PrivacyController(this);
		newsfeedController=new NewsfeedController(this);
		notificationsController=new NotificationsController(this);
		moderationController=new ModerationController(this);
		statsController=new StatsController(this);
		mailController=new MailController(this);
		searchController=new SearchController(this);
		bookmarksController=new BookmarksController(this);
		photosController=new PhotosController(this);
		commentsController=new CommentsController(this);
		boardController=new BoardController(this);
		userDataExportWorker=new UserDataExportWorker(this);
		appsController=new AppsController(this);
		mediaProcessingQueue=new MediaProcessingQueue(this);
		webPushController = new WebPushController(this);
	}

	public WallController getWallController(){
		return wallController;
	}

	public GroupsController getGroupsController(){
		return groupsController;
	}

	public UsersController getUsersController(){
		return usersController;
	}

	public UserInteractionsController getUserInteractionsController(){
		return userInteractionsController;
	}

	public FriendsController getFriendsController(){
		return friendsController;
	}

	public ObjectLinkResolver getObjectLinkResolver(){
		return objectLinkResolver;
	}

	public PrivacyController getPrivacyController(){
		return privacyController;
	}

	public NewsfeedController getNewsfeedController(){
		return newsfeedController;
	}

	public NotificationsController getNotificationsController(){
		return notificationsController;
	}

	public ModerationController getModerationController(){
		return moderationController;
	}

	public StatsController getStatsController(){
		return statsController;
	}

	public MailController getMailController(){
		return mailController;
	}

	public SearchController getSearchController(){
		return searchController;
	}

	public BookmarksController getBookmarksController(){
		return bookmarksController;
	}

	public PhotosController getPhotosController(){
		return photosController;
	}

	public CommentsController getCommentsController(){
		return commentsController;
	}

	public BoardController getBoardController(){
		return boardController;
	}

	public UserDataExportWorker getUserDataExportWorker(){
		return userDataExportWorker;
	}

	public MediaProcessingQueue getMediaProcessingQueue(){
		return mediaProcessingQueue;
	}

	public AppsController getAppsController(){
		return appsController;
	}

	public WebPushController getWebPushController() {
		return webPushController;
	}
}
