package smithereen.controllers;

import com.google.gson.JsonArray;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.LruCache;
import smithereen.model.objects.StoredObject;
import smithereen.model.objects.Actor;
import smithereen.model.media.MediaFileRef;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.exceptions.UserActionNotAllowedException;
import smithereen.exceptions.UserContentUnavailableException;
import smithereen.exceptions.UserErrorException;
import smithereen.model.CommentViewType;
import smithereen.model.Group;
import smithereen.model.OwnedContentObject;
import smithereen.model.OwnerAndAuthor;
import smithereen.model.PaginatedList;
import smithereen.model.Poll;
import smithereen.model.PollOption;
import smithereen.model.Post;
import smithereen.model.RepostPolicy;
import smithereen.model.PostLikeObject;
import smithereen.model.PostSource;
import smithereen.model.User;
import smithereen.model.UserInteractions;
import smithereen.model.UserPermissions;
import smithereen.model.UserPrivacySettingKey;
import smithereen.model.admin.UserRole;
import smithereen.model.apps.ClientApp;
import smithereen.model.feed.NewsfeedEntry;
import smithereen.model.friends.FriendshipStatus;
import smithereen.model.groups.GroupBanStatus;
import smithereen.model.groups.GroupFeatureState;
import smithereen.model.media.MediaFileReferenceType;
import smithereen.model.media.MediaFileUploadPurpose;
import smithereen.model.notifications.Notification;
import smithereen.model.viewmodel.PostViewModel;
import smithereen.storage.MediaStorage;
import smithereen.storage.MediaStorageUtils;
import smithereen.storage.NotificationsStorage;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import smithereen.text.FormattedTextFormat;
import smithereen.text.TextProcessor;
import smithereen.util.BackgroundTaskRunner;
import smithereen.util.FloodControl;
import smithereen.util.JsonObjectBuilder;
import spark.utils.StringUtils;

import static smithereen.Utils.ensureUserNotBlocked;

public class WallController{
	private static final Logger LOG=LoggerFactory.getLogger(WallController.class);
	public static final int MAX_PINNED_POSTS=5;

	private final LruCache<Integer, List<Integer>> pinnedPostIDsCache=new LruCache<>(1000);
	private final HashMap<String, PostGuidInfo> guids=new HashMap<>();

	private final ApplicationContext context;

	public WallController(ApplicationContext context){
		this.context=context;
	}

	/**
	 * Create a new wall post or comment.
	 *
	 * @param author         Post author.
	 * @param wallOwner      Wall owner (user or group). Ignored for comments.
	 * @param inReplyTo      Post this is in reply to, null for a top-level post.
	 * @param textSource     HTML post text, as entered by the user.
	 * @param contentWarning Content warning (null for none).
	 * @param attachmentIDs  IDs (hashes) of previously uploaded photo attachments.
	 * @param poll           Poll to attach.
	 * @param action
	 * @param app
	 * @param guid
	 * @return The newly created post.
	 */
	public Post createWallPost(@NotNull User author, @NotNull Actor wallOwner, Post inReplyTo,
							   @Nullable String textSource, @NotNull FormattedTextFormat sourceFormat, @Nullable String contentWarning, @NotNull List<String> attachmentIDs,
							   @Nullable Poll poll, @Nullable Post repost, @NotNull Map<String, String> attachAltTexts, Post.@Nullable Action action, @Nullable ClientApp app, @Nullable String guid){
		if(StringUtils.isNotEmpty(guid)){
			PostGuidInfo guidInfo=guids.get(guid);
			if(guidInfo!=null && guidInfo.time.isAfter(Instant.now().minus(1, ChronoUnit.HOURS))){
				try{
					return getPostOrThrow(guidInfo.postID);
				}catch(ObjectNotFoundException ignore){}
			}
		}
		try{
			if(wallOwner instanceof Group group){
				context.getPrivacyController().enforceUserAccessToGroupContent(author, group);
				if(group.wallState==GroupFeatureState.DISABLED)
					throw new UserActionNotAllowedException();
				if(inReplyTo==null){
					ensureUserNotBlocked(author, group);
					if(group.wallState==GroupFeatureState.ENABLED_CLOSED)
						context.getGroupsController().enforceUserAdminLevel(group, author, Group.AdminLevel.MODERATOR);
				}
			}else if(wallOwner instanceof User user){
				if(inReplyTo==null){
					ensureUserNotBlocked(author, user);
					if(user.id!=author.id){
						context.getPrivacyController().enforceUserPrivacy(author, user, UserPrivacySettingKey.WALL_POSTING);
						context.getPrivacyController().enforceUserPrivacy(author, user, UserPrivacySettingKey.WALL_OTHERS_POSTS);
					}
				}
			}
			if(poll!=null && (StringUtils.isEmpty(poll.question) || poll.options.size()<2)){
				LOG.warn("Invalid poll object passed to createWallPost: {}", poll);
				poll=null;
			}
			if(inReplyTo!=null && poll!=null)
				poll=null;

			if(StringUtils.isBlank(textSource) && attachmentIDs.isEmpty() && poll==null && repost==null)
				throw new BadRequestException("Empty post");

			if(inReplyTo!=null){
				context.getPrivacyController().enforcePostPrivacy(author, inReplyTo);
			}
			// Reposts only go to the reposter's own wall, and are never comments
			boolean isOnOwnWall = wallOwner instanceof User ownerUser && ownerUser.id == author.id;
			if (repost != null && (inReplyTo != null || !isOnOwnWall))
				throw new UserActionNotAllowedException();

			if (repost != null) {
				repost = getRepostTarget(repost);
				enforceCanRepost(author, repost);
				User repostAuthor = context.getUsersController().getUserOrThrow(repost.authorID);
				context.getFriendsController().incrementHintsRank(author, repostAuthor, 5);
			}

			final HashSet<User> mentionedUsers=new HashSet<>();
			String text=preparePostText(textSource, mentionedUsers, inReplyTo!=null && inReplyTo.getReplyLevel()>0 ? inReplyTo.authorID : 0, sourceFormat);
			int userID=author.id;
			int postID;

			if(poll!=null){
				if(poll.ownerID!=wallOwner.getOwnerID() || PostStorage.getPostIdByPollId(poll.id)>0)
					poll=null;
			}

			int maxAttachments=inReplyTo!=null ? 2 : 10;
			int attachmentCount=poll!=null ? 1 : 0;
			String attachments=null;
			if(!attachmentIDs.isEmpty()){
				ArrayList<MediaFileRef> attachObjects=new ArrayList<>();
				MediaStorageUtils.fillAttachmentObjects(context, author, attachObjects, attachmentIDs, attachAltTexts, attachmentCount, maxAttachments, inReplyTo==null);
				if(!attachObjects.isEmpty()){
					if(attachObjects.size()==1){
						attachments=attachObjects.getFirst().toJson().toString();
					}else{
						JsonArray ar=new JsonArray();
						for(MediaFileRef o:attachObjects){
							ar.add(o.toJson());
						}
						attachments=ar.toString();
					}
				}
			}

			if(contentWarning!=null){
				contentWarning=contentWarning.trim();
				if(contentWarning.isEmpty())
					contentWarning=null;
			}


			if(text.isEmpty() && StringUtils.isEmpty(attachments) && poll==null && repost==null)
				throw new BadRequestException("Empty post");

			EnumSet<Post.Flag> flags=EnumSet.noneOf(Post.Flag.class);

			int ownerUserID=wallOwner instanceof User u ? u.id : 0;
			int ownerGroupID=wallOwner instanceof Group g ? g.id : 0;
			boolean isTopLevelPostOwn;
			List<Integer> replyKey;
			if(inReplyTo!=null){
				replyKey=inReplyTo.getReplyKeyForReplies();
				Post topLevel;
				if(!inReplyTo.replyKey.isEmpty()){
					topLevel=getPostOrThrow(inReplyTo.replyKey.getFirst());
				}else{
					topLevel=inReplyTo;
				}
				context.getPrivacyController().enforcePostPrivacy(author, topLevel);

				OwnerAndAuthor topLevelOwnership=getContentAuthorAndOwner(topLevel);
				Actor topLevelOwner=topLevelOwnership.owner();
				User topLevelAuthor=topLevelOwnership.author();

				mentionedUsers.add(topLevelAuthor);
				if(topLevel.isGroupOwner()){
					Group group=(Group) topLevelOwner;
					if(group.wallState==GroupFeatureState.ENABLED_CLOSED || group.wallState==GroupFeatureState.DISABLED)
						throw new UserActionNotAllowedException();
					ownerGroupID=-topLevel.ownerID;
					ownerUserID=0;
					ensureUserNotBlocked(author, topLevelOwner);
					isTopLevelPostOwn=false;
				}else{
					ownerGroupID=0;
					ownerUserID=topLevel.ownerID;
					ensureUserNotBlocked(author, topLevelOwner);
					isTopLevelPostOwn=ownerUserID==topLevel.authorID;
					context.getPrivacyController().enforceUserPrivacy(author, (User)topLevelOwner, UserPrivacySettingKey.WALL_COMMENTING);
				}
				if(inReplyTo!=topLevel){
					User parentAuthor=context.getUsersController().getUserOrThrow(inReplyTo.authorID);
					context.getFriendsController().incrementHintsRank(author, parentAuthor, 3);
					context.getPrivacyController().enforcePostPrivacy(author, inReplyTo);
				}
				if(topLevelAuthor.id!=inReplyTo.authorID)
					context.getFriendsController().incrementHintsRank(author, topLevelAuthor, 5);

				if(topLevel.ownerID!=topLevel.authorID)
					flags.add(Post.Flag.TOP_IS_WALL_TO_WALL);
			}else{
				replyKey=null;
				isTopLevelPostOwn=wallOwner.getOwnerID()==author.id;
			}

			FloodControl.POSTS.incrementOrThrow(author);

			String extra=null;
			if(app!=null){
				extra=new JsonObjectBuilder()
						.add("app", app.id)
						.build()
						.toString();
			}
			postID=PostStorage.createWallPost(userID, ownerUserID, ownerGroupID, text, textSource, sourceFormat, replyKey, mentionedUsers, attachments, contentWarning, poll==null ? 0 : poll.id, repost!=null ? repost.id : 0, action, flags, extra);
			if(ownerUserID==userID && replyKey==null){
				context.getNewsfeedController().putFriendsFeedEntry(author, postID, NewsfeedEntry.Type.POST);
			}else if(wallOwner instanceof User u && replyKey==null){
				// A post on someone else's wall is news about the wall owner: it goes to the feeds of their friends and followers
				context.getNewsfeedController().putFriendsFeedEntry(u, postID, NewsfeedEntry.Type.POST);
			}else if(wallOwner instanceof Group g && replyKey==null){
				context.getNewsfeedController().putGroupsFeedEntry(g, postID, NewsfeedEntry.Type.POST);
			}

			if(wallOwner instanceof User u && u.id!=author.id && (inReplyTo==null || inReplyTo.authorID!=u.id))
				context.getFriendsController().incrementHintsRank(author, u, 3);
			else if(wallOwner instanceof Group g)
				context.getGroupsController().incrementHintsRank(author, g, 5);

			Post post=PostStorage.getPostByID(postID, false);
			if(post==null)
				throw new IllegalStateException("?!");

			if(post.attachments!=null){
				for(MediaFileRef li:post.attachments){
					MediaStorage.createMediaFileReference(li.fileID, post.id, MediaFileReferenceType.WALL_ATTACHMENT, post.getOwnerID());
				}
			}

			context.getNotificationsController().createNotificationsForObject(post);

			if(StringUtils.isNotEmpty(guid)){
				synchronized(guids){
					guids.put(guid, new PostGuidInfo(postID, Instant.now()));
				}
			}

			return post;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void removeExpiredGuids(){
		synchronized(guids){
			Instant hourAgo=Instant.now().minus(1, ChronoUnit.HOURS);
			guids.entrySet().removeIf(e->e.getValue().time.isBefore(hourAgo));
		}
	}

	public String preparePostText(String textSource, final Set<User> mentionedUsers, int parentAuthorID, @NotNull FormattedTextFormat format) throws SQLException{
		String text=TextProcessor.preprocessPostText(textSource, new TextProcessor.MentionCallback(){
			@Override
			public User resolveMention(String username, String domain){
				try{
					if(domain==null){
						User user=UserStorage.getByUsername(username);
						if(user!=null)
							mentionedUsers.add(user);
						return user;
					}
					if(domain.equalsIgnoreCase(Config.domain)){
						User user=UserStorage.getByUsername(username);
						if(user!=null)
							mentionedUsers.add(user);
						return user;
					}
				}catch(Exception x){
					LOG.warn("Can't resolve {}@{}", username, domain, x);
				}
				return null;
			}

			@Override
			public User resolveMention(String uri){
				try{
					URI u=new URI(uri);
					if("acct".equalsIgnoreCase(u.getScheme())){
						if(u.getSchemeSpecificPart().contains("@")){
							String[] parts=u.getSchemeSpecificPart().split("@");
							return resolveMention(parts[0], parts[1]);
						}
						return resolveMention(u.getSchemeSpecificPart(), null);
					}
					if(Config.isLocal(u)){
						User user=context.getObjectLinkResolver().resolveLocally(u, User.class);
						mentionedUsers.add(user);
						return user;
					}
				}catch(Exception x){
					LOG.warn("Can't resolve {}", uri, x);
				}
				return null;
			}
		}, format);

		if(parentAuthorID>0){
			// comment replies start with mentions, but only if it's a reply to a comment, not a top-level post
			User parentAuthor=context.getUsersController().getUserOrThrow(parentAuthorID);
			String nameForReply=TextProcessor.escapeHTML(parentAuthor.getNameForReply());
			if(text.startsWith("<p>"+nameForReply+",")){
				text="<p><a href=\""+TextProcessor.escapeHTML(parentAuthor.url.toString())+"\" class=\"mention\" data-user-id=\""+parentAuthor.id+"\">"
						+nameForReply+"</a>"+text.substring(nameForReply.length()+3);
			}
			mentionedUsers.add(parentAuthor);
		}

		return text;
	}

	/**
	 * Get a post by ID.
	 * @param id Post ID
	 * @return The post
	 * @throws ObjectNotFoundException if the post does not exist or was deleted
	 */
	@NotNull
	public Post getPostOrThrow(int id){
		return getPostOrThrow(id, false);
	}

	/**
	 * Get a post by ID.
	 *
	 * @param id          Post ID
	 * @param wantDeleted whether to return the post even if it was deleted
	 * @return The post
	 * @throws ObjectNotFoundException if the post does not exist or was deleted
	 */
	@NotNull
	public Post getPostOrThrow(int id, boolean wantDeleted){
		if(id<=0)
			throw new ObjectNotFoundException("err_post_not_found");
		try{
			Post post=PostStorage.getPostByID(id, wantDeleted);
			if(post==null)
				throw new ObjectNotFoundException("err_post_not_found");
			return post;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	@NotNull
	public Post getLocalPostOrThrow(int id){
		Post post=getPostOrThrow(id);
		return post;
	}

	@NotNull
	public Post editPost(@NotNull User self, @NotNull UserPermissions permissions, int id, @Nullable String textSource, @NotNull FormattedTextFormat sourceFormat, @Nullable String contentWarning,
						 @NotNull List<String> attachmentIDs, @Nullable Poll poll, @NotNull Map<String, String> attachAltTexts){
		try{
			Post post=getPostOrThrow(id);
			if(!permissions.canEditPost(post))
				throw new UserActionNotAllowedException();
			context.getPrivacyController().enforceObjectPrivacy(self, post);
			if(post.getReplyLevel()>0)
				poll=null;
			if(StringUtils.isEmpty(textSource) && attachmentIDs.isEmpty() && poll==null && post.repostOf==0)
				throw new BadRequestException("Empty post");

			HashSet<User> mentionedUsers=new HashSet<>();
			Post parent=post.getReplyLevel()>0 ? getPostOrThrow(post.getReplyChainElement(post.getReplyLevel()-1)) : null;
			String text=preparePostText(textSource, mentionedUsers, parent!=null && parent.getReplyLevel()>1 ? parent.authorID : 0, sourceFormat);

			if(poll!=null && (post.poll==null || poll.id!=post.poll.id)){
				if(poll.ownerID!=post.ownerID || PostStorage.getPostIdByPollId(poll.id)>0)
					poll=null;
			}
			if(post.poll!=null && (poll==null || poll.id!=post.poll.id)){
				PostStorage.deletePoll(post.poll.id);
			}

			int maxAttachments=parent!=null ? 2 : 10;
			int attachmentCount=poll!=null ? 1 : 0;
			String attachments=null;
			ArrayList<MediaFileRef> attachObjects=new ArrayList<>();

			ArrayList<String> newlyAddedAttachments=new ArrayList<>(attachmentIDs);
			if(post.attachments!=null){
				for(MediaFileRef li:post.attachments){
					String localID=li.getLocalID(MediaFileUploadPurpose.ATTACHMENT, self.id);
					if(!newlyAddedAttachments.remove(localID)){
						LOG.debug("Deleting attachment: {}", localID);
						MediaStorage.deleteMediaFileReference(post.id, MediaFileReferenceType.WALL_ATTACHMENT, li.fileID);
					}else{
						li.name=attachAltTexts.get(localID);
						attachObjects.add(li);
					}
				}
			}

			if(!newlyAddedAttachments.isEmpty()){
				MediaStorageUtils.fillAttachmentObjects(context, self, attachObjects, newlyAddedAttachments, attachAltTexts, attachmentCount, maxAttachments, post.getReplyLevel()==0);
				for(MediaFileRef li:attachObjects){
					if(newlyAddedAttachments.contains(li.getLocalID(MediaFileUploadPurpose.ATTACHMENT, self.id))){
						MediaStorage.createMediaFileReference(li.fileID, post.id, MediaFileReferenceType.WALL_ATTACHMENT, post.ownerID);
					}
				}
			}
			if(!attachObjects.isEmpty()){
				if(attachObjects.size()==1){
					attachments=attachObjects.getFirst().toJson().toString();
				}else{
					JsonArray ar=new JsonArray();
					for(MediaFileRef o:attachObjects){
						ar.add(o.toJson());
					}
					attachments=ar.toString();
				}
			}

			PostStorage.updateWallPost(id, text, textSource, sourceFormat, mentionedUsers, attachments, contentWarning, poll==null ? 0 : poll.id);
			if(post.ownerID>0 && post.ownerID==post.authorID){
				context.getNewsfeedController().clearFriendsFeedCache();
			}

			post=getPostOrThrow(id);
			return post;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	/**
	 * Get posts from a wall.
	 * @param owner Wall owner, either a user or a group
	 * @param mode Whether to return only user's own posts or include other's posts
	 * @param offset Pagination offset
	 * @param count Maximum number of posts to return
	 * @return A reverse-chronologically sorted paginated list of wall posts
	 */
	public PaginatedList<Post> getWallPosts(@Nullable User self, @NotNull Actor owner, WallMode mode, int offset, int count){
		try{
			int[] postCount={0};
			Set<Post.Privacy> allowedPrivacy;
			if(self!=null && owner instanceof User ownerUser){
				if(self.id==owner.getOwnerID()){
					allowedPrivacy=EnumSet.allOf(Post.Privacy.class);
				}else{
					FriendshipStatus status=context.getFriendsController().getSimpleFriendshipStatus(self, ownerUser);
					allowedPrivacy=switch(status){
						case FOLLOWING -> EnumSet.of(Post.Privacy.PUBLIC, Post.Privacy.FOLLOWERS_ONLY, Post.Privacy.FOLLOWERS_AND_MENTIONED);
						case FRIENDS -> EnumSet.of(Post.Privacy.PUBLIC, Post.Privacy.FOLLOWERS_ONLY, Post.Privacy.FOLLOWERS_AND_MENTIONED, Post.Privacy.FRIENDS_ONLY);
						default -> EnumSet.of(Post.Privacy.PUBLIC);
					};
				}
			}else{
				allowedPrivacy=EnumSet.of(Post.Privacy.PUBLIC);
			}
			List<Post> wall=PostStorage.getWallPosts(owner.getLocalID(), owner instanceof Group, 0, 0, offset, count, postCount, mode, allowedPrivacy);
			return new PaginatedList<>(wall, postCount[0], offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	/**
	 * Get posts that two users posted on each other's walls.
	 * @param user The first user
	 * @param otherUser The second user
	 * @param offset Pagination offset
	 * @param count Maximum number of posts to return
	 * @return A reverse-chronologically sorted paginated list of wall posts
	 */
	public PaginatedList<Post> getWallToWallPosts(@Nullable User self, @NotNull User user, @NotNull User otherUser, int offset, int count){
		try{
			context.getPrivacyController().enforceUserPrivacy(self, user, UserPrivacySettingKey.WALL_OTHERS_POSTS);
			context.getPrivacyController().enforceUserPrivacy(self, otherUser, UserPrivacySettingKey.WALL_OTHERS_POSTS);
			int[] postCount={0};
			List<Post> wall=PostStorage.getWallToWall(user.id, otherUser.id, offset, count, postCount);
			return new PaginatedList<>(wall, postCount[0], offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	/**
	 * Add top-level comments to each post.
	 * @param posts List of posts to add comments to
	 */
	public void populateCommentPreviews(@Nullable User self, @NotNull List<PostViewModel> posts, CommentViewType viewType){
		populateCommentPreviews(self, posts, viewType, 3);
	}

	/**
	 * Add top-level comments to each post.
	 *
	 * @param posts List of posts to add comments to
	 * @param count
	 */
	public void populateCommentPreviews(@Nullable User self, @NotNull List<PostViewModel> posts, CommentViewType viewType, int count){
		try{
			Set<List<Integer>> postIDs=posts.stream().map(PostViewModel::getReplyKeyForInteractions).collect(Collectors.toSet());
			Map<Integer, PaginatedList<Post>> allComments=PostStorage.getRepliesForFeed(postIDs, viewType==CommentViewType.FLAT, count);
			List<PostViewModel> commentsThatNeedAuthors=null;
			if(viewType==CommentViewType.FLAT){
				commentsThatNeedAuthors=new ArrayList<>();
			}
			for(PostViewModel post:posts){
				PaginatedList<Post> comments=allComments.get(post.post.getIDForInteractions());
				if(comments!=null){
					context.getPrivacyController().filterPostsForPerPostPrivacy(self, comments.list);
					if(!comments.list.isEmpty()){
						post.repliesObjects=comments.list.stream().map(PostViewModel::new).toList();
						post.totalTopLevelComments=comments.total;
						if(viewType==CommentViewType.FLAT){
							commentsThatNeedAuthors.addAll(post.repliesObjects);
						}
					}
				}
			}
			if(viewType==CommentViewType.FLAT){
				fillInParentAuthors(commentsThatNeedAuthors, null);
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	/**
	 * Get {@link UserInteractions} for posts.
	 * @param posts List of posts to get user interactions for
	 * @param self Current user to check whether posts are liked
	 * @return A map from a post ID to a {@link UserInteractions} object for each post
	 */
	public Map<Integer, UserInteractions> getUserInteractions(@NotNull List<PostViewModel> posts, @Nullable User self){
		try{
			HashSet<Integer> postIDs=new HashSet<>();
			for(PostViewModel p:posts){
				postIDs.add(p.post.getIDForInteractions());
				if(p.repost!=null){
					p.collectRepostIDs(postIDs);
				}
			}
			Set<Integer> ownerUserIDs=new HashSet<>(), ownerGroupIDs=new HashSet<>();
			for(PostViewModel p:posts){
				p.getAllReplyIDs(postIDs);
				if(p.post.ownerID>0)
					ownerUserIDs.add(p.post.ownerID);
				else if(p.post.ownerID<0)
					ownerGroupIDs.add(-p.post.ownerID);

			}
			Map<Integer, Boolean> canComment=context.getUsersController().getUsers(ownerUserIDs)
					.entrySet()
					.stream()
					.collect(Collectors.toMap(Map.Entry::getKey, e->context.getPrivacyController().checkUserPrivacy(self, e.getValue(), UserPrivacySettingKey.WALL_COMMENTING)));

			Set<Integer> blockingUsers, blockingGroups;
			if(self!=null){
				blockingUsers=context.getUsersController().getBlockingUsers(self, ownerUserIDs);
				blockingGroups=context.getGroupsController().getBlockingGroups(self, ownerGroupIDs);
			}else{
				blockingGroups=blockingUsers=Set.of();
			}

			if(!ownerGroupIDs.isEmpty()){
				canComment=new HashMap<>(canComment);
				for(Group group:context.getGroupsController().getGroupsByIdAsList(ownerGroupIDs)){
					if(group==null)
						continue;
					canComment.put(-group.id, group.wallState!=GroupFeatureState.ENABLED_CLOSED);
				}
			}

			List<PostViewModel> allPosts=posts.stream().flatMap(pvm->{
				ArrayList<PostViewModel> replies=new ArrayList<>();
				replies.add(pvm);
				pvm.getAllReplies(replies);
				return replies.stream();
			}).toList();
			Map<Integer, PostViewModel> postsByID=allPosts.stream().collect(Collectors.toMap(pvm->pvm.post.id, Function.identity(), (p1, p2)->p1));
			Map<Integer, UserInteractions> interactions=PostStorage.getPostInteractions(postIDs, self!=null ? self.id : 0);
			Set<Integer> repostablePostIDs = getRepostablePostIDs(allPosts, postsByID);

			for(PostViewModel post:allPosts){
				UserInteractions ui=interactions.get(post.post.getIDForInteractions());
				int ownerID;
				ownerID=post.post.ownerID;


				// Can't comment on posts or in threads that don't exist
				if(false && post.repost!=null && (post.repost.post()==null || (post.repost.post().post.getReplyLevel()>0 && post.repost.topLevel()==null)))
					ui.canComment=false;
				else
					ui.canComment=canComment.getOrDefault(ownerID, true);

				ui.canRepost = repostablePostIDs.contains(post.post.id);

				if(self==null)
					continue;

				if(post.post.ownerID>0)
					ui.canLike=!blockingUsers.contains(post.post.ownerID);
				else
					ui.canLike=!blockingGroups.contains(-post.post.ownerID);
			}
			return interactions;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void sendUpdateQuestionIfNeeded(Post post){
		if(post.poll==null)
			throw new IllegalArgumentException("Post must have a poll");

		if(post.poll.lastVoteTime.until(Instant.now(), ChronoUnit.MINUTES)>=5){
			BackgroundTaskRunner.getInstance().submitDelayed(()->{
				try{
					// Get post again so the poll inside is up-to-date.
				}catch(Exception x){
				  LOG.warn("Error sending Update{Question}", x);
				}
			}, 5, TimeUnit.MINUTES);
		}
	}

	public Map<Integer, Post> getPosts(Collection<Integer> ids){
		if(ids.isEmpty())
			return Map.of();
		try{
			return PostStorage.getPostsByID(ids);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PostSource getPostSource(Post post){
		try{
			return PostStorage.getPostSource(post.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<PostViewModel> getReplies(@Nullable User self, List<Integer> key, int primaryOffset, int primaryCount, int secondaryCount, CommentViewType type, boolean reversed){
		try{
			LOG.trace("Getting post replies: priOffset={}, priCount={}, secCount={}, key={}, type={}", primaryOffset, primaryCount, secondaryCount, key, type);
			Post threadParent=PostStorage.getPostByID(key.getLast(), true);
			if(threadParent==null)
				throw new ObjectNotFoundException();

			// For two-level and flat, if we're getting replies to a comment, always treat them as a flat list
			if((type==CommentViewType.TWO_LEVEL && threadParent.getReplyLevel()>0) || type==CommentViewType.FLAT){
				PaginatedList<PostViewModel> posts=PostViewModel.wrap(PostStorage.getRepliesFlat(key, primaryOffset, primaryCount, reversed));
				context.getPrivacyController().filterPostViewModelsForPerPostPrivacy(self, posts.list);
				fillInParentAuthors(posts.list, threadParent);
				return posts;
			}
			PostStorage.ThreadedReplies tr=PostStorage.getRepliesThreaded(key, primaryOffset, primaryCount, secondaryCount, type==CommentViewType.TWO_LEVEL, reversed);

			List<PostViewModel> posts=tr.posts().stream().filter(p->context.getPrivacyController().checkPostPrivacy(self, p)).map(PostViewModel::new).toList();
			List<PostViewModel> replies=tr.replies().stream().filter(p->context.getPrivacyController().checkPostPrivacy(self, p)).map(PostViewModel::new).toList();
			Map<Integer, PostViewModel> postMap=Stream.of(posts, replies).flatMap(List::stream).collect(Collectors.toMap(p->p.post.id, Function.identity()));

			for(PostViewModel post:replies){
				if(post.post.getReplyLevel()>key.size()){
					PostViewModel parent=switch(type){
						case THREADED -> postMap.get(post.post.replyKey.getLast());
						case TWO_LEVEL -> postMap.get(post.post.replyKey.get(1));
						case FLAT -> throw new IllegalArgumentException();
					};
					if(parent!=null){
						PostViewModel realParent=postMap.get(post.post.replyKey.getLast());
						if(realParent!=null)
							post.parentAuthorID=realParent.post.authorID;
						parent.repliesObjects.add(post);
					}
				}
			}
			for(PostViewModel post:posts){
				post.parentAuthorID=threadParent.authorID;
			}

			return new PaginatedList<>(posts, tr.total(), primaryOffset, primaryCount);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<PostViewModel> getRepliesFlat(@Nullable User self, List<Integer> key, int maxID, int count){
		try{
			PaginatedList<PostViewModel> posts=PostViewModel.wrap(PostStorage.getRepliesFlatWithMaxID(key, maxID, count));
			context.getPrivacyController().filterPostViewModelsForPerPostPrivacy(self, posts.list);
			fillInParentAuthors(posts.list, null);
			return posts;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	/**
	 * Fills in {@link PostViewModel#parentAuthorID} for comments loaded outside of a comment thread, for example the ones
	 * referenced by notifications, so that the API can tell whose comment each of them replies to.
	 */
	public void fillInParentAuthors(List<PostViewModel> comments) {
		try {
			fillInParentAuthors(comments, null);
		} catch (SQLException x) {
			throw new InternalServerErrorException(x);
		}
	}

	private void fillInParentAuthors(List<PostViewModel> posts, Post threadParent) throws SQLException{
		HashMap<Integer, Integer> authorIDs=new HashMap<>();
		if(threadParent!=null)
			authorIDs.put(threadParent.id, threadParent.authorID);
		for(PostViewModel p:posts){
			authorIDs.put(p.post.id, p.post.authorID);
		}
		HashSet<Integer> needAuthors=new HashSet<>();
		for(PostViewModel p:posts){
			int author=authorIDs.getOrDefault(p.post.replyKey.getLast(), 0);
			if(author!=0){
				p.parentAuthorID=author;
			}else{
				needAuthors.add(p.post.replyKey.getLast());
			}
		}
		if(!needAuthors.isEmpty()){
			Map<Integer, Integer> moreAuthorIDs=PostStorage.getPostAuthors(needAuthors);
			for(PostViewModel p:posts){
				if(p.parentAuthorID==0){
					p.parentAuthorID=moreAuthorIDs.getOrDefault(p.post.replyKey.getLast(), 0);
				}
			}
		}
	}

	public PaginatedList<Post> getRepliesExact(@Nullable User self, List<Integer> key, int maxID, int count){
		try{
			PaginatedList<Post> posts=PostStorage.getRepliesExact(key.stream().mapToInt(Integer::intValue).toArray(), maxID, count);
			posts.list=new ArrayList<>(posts.list);
			context.getPrivacyController().filterPostsForPerPostPrivacy(self, posts.list);
			return posts;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void deletePost(@NotNull User self, Post post){
		deletePostInternal(self, post, false);
	}

	public void deletePostAsServerModerator(@NotNull User self, Post post){
		deletePostInternal(self, post, true);
	}

	private void deletePostInternal(@NotNull User self, Post post, boolean ignorePermissions){
		try{
			OwnerAndAuthor oaa;
			try{
				oaa=getContentAuthorAndOwner(post);
			}catch(ObjectNotFoundException x){
				oaa=new OwnerAndAuthor(null, null);
			}
			if(!ignorePermissions){
				context.getPrivacyController().enforceObjectPrivacy(self, post);
				if(post.ownerID!=self.id && post.authorID!=self.id){ // Can always delete own posts and others' posts on own wall
					if(post.ownerID>0) // Can't delete posts not made of owned oneself
						throw new UserActionNotAllowedException();
					else // Must be at least a moderator to delete others' posts in groups
						context.getGroupsController().enforceUserAdminLevel((Group)oaa.owner(), self, Group.AdminLevel.MODERATOR);
				}
			}
			PostStorage.deletePost(post.id);
			NotificationsStorage.deleteNotificationsForObject(Notification.ObjectType.POST, post.id);
			context.getNewsfeedController().clearFriendsFeedCache();
			if(post.ownerID<0 && post.getReplyLevel()==0){
				context.getNewsfeedController().clearGroupsFeedCache();
			}
			User deleteActor=self;
			// if the current user is a moderator, and the post isn't made or owned by them, send the deletion as if the author deleted the post themselves
			if(ignorePermissions && post.authorID!=self.id && !post.isGroupOwner() && post.ownerID!=self.id){
				deleteActor=oaa.author();
			}
			if(post.attachments!=null){
				MediaStorage.deleteMediaFileReferences(post.id, MediaFileReferenceType.WALL_ATTACHMENT);
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public List<Integer> getPollOptionVoters(PollOption option, int offset, int count){
		try{
			return PostStorage.getPollOptionVoters(option.id, offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void setPostCWAsModerator(@NotNull UserPermissions permissions, Post post, String cw){
		try{
			if(!permissions.hasPermission(UserRole.Permission.MANAGE_REPORTS))
				throw new UserActionNotAllowedException();

			PostStorage.updateWallPostCW(post.id, cw);

			if(!post.isGroupOwner() && post.ownerID==post.authorID){
				context.getNewsfeedController().clearFriendsFeedCache();
			}

			post=getPostOrThrow(post.id);

		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	/**
	 * Whether {@code post} is a repost that adds nothing of its own (no text, attachments or poll). Sharing such a
	 * repost shares its original instead, so chains of empty reposts don't build up.
	 */
	public static boolean isRepostWithoutOwnContent(Post post) {
		boolean hasText = post.text != null && !TextProcessor.stripHTML(post.text, false).isBlank();
		boolean hasAttachments = post.attachments != null && !post.attachments.isEmpty();
		return post.repostOf != 0 && !hasText && !hasAttachments && post.poll == null;
	}

	/**
	 * The post that sharing {@code post} actually reposts: its original if it's a repost without a comment, otherwise
	 * the post itself.
	 */
	public Post getRepostTarget(Post post) {
		return isRepostWithoutOwnContent(post) ? getPostOrThrow(post.repostOf) : post;
	}

	/**
	 * Throws unless {@code self} may share {@code post} to their wall: the post must pass {@link RepostPolicy},
	 * {@code self} must be able to see it, and neither its author nor the owner of its wall may have blocked
	 * {@code self}.
	 */
	public void enforceCanRepost(@NotNull User self, @NotNull Post post) {
		if (post.isDeleted())
			throw new ObjectNotFoundException();
		Post topLevel = post.getReplyLevel() > 0 ? getPostOrThrow(post.replyKey.getFirst()) : post;
		OwnerAndAuthor ownership = getContentAuthorAndOwner(post);
		RepostPolicy.Candidate candidate = RepostPolicy.Candidate.ofExisting(post, topLevel, ownership.owner())
				.orElseThrow(ObjectNotFoundException::new);
		if (RepostPolicy.check(candidate) != RepostPolicy.Verdict.ALLOWED)
			throw new UserActionNotAllowedException();

		// The reposter must be able to see the wall the post is on (the post itself is public, the policy checked that)
		ArrayList<Post> visibleOnItsWall = new ArrayList<>(List.of(post));
		context.getPrivacyController().filterPostsForOwnerPrivacy(self, visibleOnItsWall); // removes the post if not
		if (visibleOnItsWall.isEmpty())
			throw new UserContentUnavailableException();

		// and must not be blocked by its author or by the owner of its wall
		if (ownership.author() != null)
			context.getUsersController().ensureUserNotBlocked(self, ownership.author());
		if (ownership.owner().getOwnerID() != post.authorID)
			context.getUsersController().ensureUserNotBlocked(self, ownership.owner());
	}

	/**
	 * IDs of the posts in {@code posts} that get a share button. A repost without a comment of its own gets one when
	 * its original qualifies, because sharing it shares the original. The top-level posts and wall owners the check
	 * needs are loaded for the whole batch at once.
	 *
	 * @param knownPosts Posts already loaded, by ID, so their top-level posts don't need fetching.
	 */
	private Set<Integer> getRepostablePostIDs(Collection<PostViewModel> posts, Map<Integer, PostViewModel> knownPosts) {
		// Key: the post with the share button; value: what the button would repost
		Map<Integer, Post> targets = new HashMap<>();
		for (PostViewModel pvm : posts) {
			Post target = getLoadedRepostTarget(pvm);
			if (target != null)
				targets.put(pvm.post.id, target);
		}
		Map<Integer, Post> topLevels = loadTopLevelPosts(targets.values(), knownPosts);
		Map<Integer, Actor> owners = loadWallOwners(targets.values());

		Set<Integer> repostable = new HashSet<>();
		targets.forEach((postID, target) -> {
			if (isRepostable(target, topLevels, owners))
				repostable.add(postID);
		});
		return repostable;
	}

	private boolean isRepostable(Post post, Map<Integer, Post> topLevels, Map<Integer, Actor> owners) {
		int topLevelID = post.getReplyLevel() > 0 ? post.replyKey.getFirst() : post.id;
		try {
			return RepostPolicy.Candidate.ofExisting(post, topLevels.get(topLevelID), owners.get(post.ownerID))
					.map(candidate -> RepostPolicy.check(candidate) == RepostPolicy.Verdict.ALLOWED)
					.orElse(false);
		} catch (IllegalArgumentException x) {
			// An inconsistent row (say, a comment stored with another owner than its post) costs that post its share
			// button, not the whole page
			LOG.warn("Can't decide whether post {} can be reposted", post.id, x);
			return false;
		}
	}

	/**
	 * Like {@link #getRepostTarget}, but only from what {@link #populateReposts} already loaded into {@code pvm}.
	 *
	 * @return The post to repost, or null when it's a repost without a comment whose original wasn't loaded or is
	 * unavailable to the viewer.
	 */
	private static @Nullable Post getLoadedRepostTarget(PostViewModel pvm) {
		if (!isRepostWithoutOwnContent(pvm.post))
			return pvm.post;
		if (pvm.repost == null || pvm.repost.post() == null)
			return null;
		return pvm.repost.post().post;
	}

	/**
	 * The top-level post of each of {@code posts}, by ID: the post itself, or for a comment the post it was written
	 * under. Top-level posts that were not found are missing from the map.
	 */
	private Map<Integer, Post> loadTopLevelPosts(Collection<Post> posts, Map<Integer, PostViewModel> knownPosts) {
		Map<Integer, Post> topLevels = new HashMap<>();
		Set<Integer> needFetching = new HashSet<>();
		for (Post post : posts) {
			if (post.getReplyLevel() == 0) {
				topLevels.put(post.id, post);
				continue;
			}
			int topLevelID = post.replyKey.getFirst();
			if (knownPosts.get(topLevelID) instanceof PostViewModel known)
				topLevels.put(topLevelID, known.post);
			else
				needFetching.add(topLevelID);
		}
		needFetching.removeAll(topLevels.keySet());
		if (!needFetching.isEmpty())
			topLevels.putAll(getPosts(needFetching));
		return topLevels;
	}

	/**
	 * The owners of the walls {@code posts} are on, by owner ID (positive for users, negative for groups, the same as
	 * {@link Post#ownerID}). Owners that were not found are missing from the map.
	 */
	private Map<Integer, Actor> loadWallOwners(Collection<Post> posts) {
		Set<Integer> userIDs = new HashSet<>(), groupIDs = new HashSet<>();
		for (Post post : posts) {
			if (post.ownerID > 0)
				userIDs.add(post.ownerID);
			else if (post.ownerID < 0)
				groupIDs.add(-post.ownerID);
		}
		Map<Integer, Actor> owners = new HashMap<>();
		for (User user : context.getUsersController().getUsers(userIDs).values()) {
			if (user != null)
				owners.put(user.getOwnerID(), user);
		}
		for (Group group : context.getGroupsController().getGroupsByIdAsMap(groupIDs).values()) {
			if (group != null)
				owners.put(group.getOwnerID(), group);
		}
		return owners;
	}

	@NotNull
	public OwnerAndAuthor getContentAuthorAndOwner(OwnedContentObject post){
		int ownerID=post.getOwnerID();
		int authorID=post.getAuthorID();
		Actor owner;
		User author;
		if(ownerID<0)
			owner=context.getGroupsController().getGroupOrThrow(-ownerID);
		else
			owner=context.getUsersController().getUserOrThrow(ownerID);
		if(authorID!=0){
			try{
				author=context.getUsersController().getUserOrThrow(authorID);
			}catch(ObjectNotFoundException x){
				author=null;
			}
		}else{
			author=null;
		}
		return new OwnerAndAuthor(owner, author);
	}

	public void populateReposts(User self, Collection<PostViewModel> posts, int maxDepth){
		HashMap<Integer, PostViewModel> knownPosts=posts.stream().collect(Collectors.toMap(p->p.post.id, Function.identity(), (a, b)->b, HashMap::new));
		HashSet<Integer> needPosts=new HashSet<>();
		HashSet<PostViewModel> reposts=new HashSet<>(), nextReposts=new HashSet<>();
		for(PostViewModel post:posts){
			if(post.post.repostOf!=0){
				reposts.add(post);
			}
		}
		for(int i=0;i<maxDepth;i++){
			needPosts.clear();
			nextReposts.clear();
			for(PostViewModel post:reposts){
				if(!knownPosts.containsKey(post.post.repostOf)){
					needPosts.add(post.post.repostOf);
				}
			}
			if(!needPosts.isEmpty()){
				Map<Integer, Post> newPosts = getPosts(needPosts);
				needPosts.clear();
				// For comments, get their top-level posts
				for (Post post : newPosts.values()) {
					if (post.getReplyLevel() > 0 && !knownPosts.containsKey(post.replyKey.getFirst()))
						needPosts.add(post.replyKey.getFirst());
				}
				Map<Integer, Post> topLevels = needPosts.isEmpty() ? Map.of() : getPosts(needPosts);
				// Originals the viewer may not see stay out of knownPosts, so their reposts get the placeholder below
				for (Post post : filterRepostedPostsForViewer(self, newPosts.values(), knownPosts, topLevels)) {
					knownPosts.put(post.id, new PostViewModel(post));
					if (post.getReplyLevel() > 0 && topLevels.get(post.replyKey.getFirst()) instanceof Post topLevel)
						knownPosts.putIfAbsent(topLevel.id, new PostViewModel(topLevel));
				}
			}
			for(PostViewModel post:reposts){
				PostViewModel repost=knownPosts.get(post.post.repostOf);
				if(repost!=null){
					PostViewModel topLevel=repost.post.getReplyLevel()>0 ? knownPosts.get(repost.post.replyKey.getFirst()) : null;
					post.repost=new PostViewModel.Repost(repost, topLevel);
					// Only a repost has a further level to load; an original would look like one whose original is gone
					if (repost.post.repostOf != 0)
						nextReposts.add(repost);
				} else {
					post.repostUnavailable = true;
				}
			}
			HashSet<PostViewModel> tmp=reposts;
			reposts=nextReposts;
			nextReposts=tmp;
		}
	}

	/**
	 * The reposted originals that {@code self} may see. A repost is shown to everyone who can see the reposter's wall,
	 * but the original can stop being public after it was shared: its group becomes closed, its wall owner hides
	 * others' posts, it gets deleted. Those originals are left out and the repost shows a
	 * placeholder instead of their content.
	 *
	 * @param knownPosts Posts already on the page, which the viewer can see.
	 * @param topLevels  The fetched top-level posts of comments in {@code originals}.
	 */
	private List<Post> filterRepostedPostsForViewer(@Nullable User self, Collection<Post> originals, Map<Integer, PostViewModel> knownPosts, Map<Integer, Post> topLevels) {
		ArrayList<Post> visible = new ArrayList<>(originals);
		visible.removeIf(Post::isDeleted);
		// The checks a wall applies: the post's own privacy, then whether the viewer may see posts on that wall
		context.getPrivacyController().filterPostsForPerPostPrivacy(self, visible);
		context.getPrivacyController().filterPostsForOwnerPrivacy(self, visible);
		// That check lets through groups that were banned since; their own pages refuse to open, so hide their posts too
		Set<Integer> groupIDs = visible.stream().filter(Post::isGroupOwner).map(post -> -post.ownerID).collect(Collectors.toSet());
		Set<Integer> bannedGroupIDs = context.getGroupsController().getGroupsByIdAsMap(groupIDs).values().stream()
				.filter(group -> group != null && group.banStatus != GroupBanStatus.NONE)
				.map(group -> group.id)
				.collect(Collectors.toSet());
		visible.removeIf(post -> post.isGroupOwner() && bannedGroupIDs.contains(-post.ownerID));
		// A comment also needs the post it was written under to be visible
		visible.removeIf(post -> {
			if (post.getReplyLevel() == 0)
				return false;
			int topLevelID = post.replyKey.getFirst();
			if (knownPosts.containsKey(topLevelID))
				return false; // already on the page, so the viewer can see it
			Post topLevel = topLevels.get(topLevelID);
			// A top-level post that wasn't found (deleted) doesn't hide the comment: the repost header says it's gone
			return topLevel != null && !context.getPrivacyController().checkPostPrivacy(self, topLevel);
		});
		return visible;
	}

	public PaginatedList<Post> getPostReposts(Post post, int offset, int count){
		try{
			return PostStorage.getPostReposts(post.getIDForInteractions(), offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public Map<Integer, PostViewModel> getUserReplies(User self, Collection<List<Integer>> replyKeys){
		if(replyKeys.isEmpty())
			return Map.of();
		try{
			List<Post> posts=PostStorage.getUserReplies(self.id, replyKeys);
			return posts.stream().collect(Collectors.toMap(p->p.replyKey.getLast(), PostViewModel::new, (p1, p2)->p1.post.id>p2.post.id ? p1 : p2));
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<PostViewModel> getAllPostsByAuthor(User user, int offset, int count){
		try{
			PaginatedList<Post> posts=PostStorage.getAllPostsByAuthor(user.id, offset, count);
			return PostViewModel.wrap(posts);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public int getLocalPostCount(boolean comments){
		try{
			return PostStorage.getLocalPostCount(comments);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	// region Pinned posts

	public List<Integer> getPinnedPostIDs(User owner){
		return getPinnedPostIDs(owner.id);
	}

	public List<Integer> getPinnedPostIDs(int ownerID){
		List<Integer> ids=pinnedPostIDsCache.get(ownerID);
		if(ids!=null)
			return ids;
		try{
			ids=PostStorage.getPinnedPostIDs(ownerID);
			pinnedPostIDsCache.put(ownerID, ids);
			return ids;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public List<Post> getPinnedPosts(User self, User owner){
		List<Integer> ids=getPinnedPostIDs(owner);
		Map<Integer, Post> postsByID=getPosts(ids);
		List<Post> posts=ids.stream().map(postsByID::get).filter(Objects::nonNull).toList();
		boolean hasPrivate=false;
		for(Post p:posts){
			if(p.privacy!=Post.Privacy.PUBLIC){
				hasPrivate=true;
				break;
			}
		}
		if(hasPrivate){
			posts=new ArrayList<>(posts);
			context.getPrivacyController().filterPostsForPerPostPrivacy(self, posts);
		}
		return posts;
	}

	public boolean isPostPinned(Post post){
		try{
			return PostStorage.isPostPinned(post.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void pinPost(Post post, boolean keepPrevious){
		try{
			User author=context.getUsersController().getUserOrThrow(post.authorID);
			if(!keepPrevious){
				List<Post> currentPosts=getPinnedPosts(author, author);
				for(Post oldPost:currentPosts){
				}
			}
			PostStorage.pinPost(post.authorID, post.id, keepPrevious);
			pinnedPostIDsCache.remove(author.id);

		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void unpinPost(Post post){
		try{
			PostStorage.unpinPost(post.authorID, post.id);
			User author=context.getUsersController().getUserOrThrow(post.authorID);
			pinnedPostIDsCache.remove(author.id);

		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void clearPinnedPosts(User owner){
		try{
			PostStorage.clearPinnedPosts(owner.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	// endregion
	// region Polls

	public Poll getPollByID(int id){
		try{
			Poll poll=PostStorage.getPoll(id);
			if(poll==null)
				throw new ObjectNotFoundException();
			return poll;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void voteInPoll(User self, Poll poll, int[] optionIDs){
		try{
			int postID=PostStorage.getPostIdByPollId(poll.id);
			if(postID==0)
				throw new UserActionNotAllowedException();

			Post post=getPostOrThrow(postID);
			context.getPrivacyController().enforcePostPrivacy(self, post);

			if(poll.isExpired())
				throw new UserErrorException("err_poll_expired");

			Actor owner;
			if(poll.ownerID>0){
				User _owner=UserStorage.getById(poll.ownerID);
				ensureUserNotBlocked(self, _owner);
				owner=_owner;
			}else{
				Group _owner=context.getGroupsController().getGroupOrThrow(-poll.ownerID);
				ensureUserNotBlocked(self, _owner);
				context.getPrivacyController().enforceUserAccessToGroupContent(self, _owner);
				owner=_owner;
			}

			List<PollOption> options=new ArrayList<>(optionIDs.length);
			for(int optID:optionIDs){
				PollOption option=null;
				for(PollOption opt:poll.options){
					if(opt.id==optID){
						option=opt;
						break;
					}
				}
				if(option==null)
					throw new BadRequestException("option with id "+optID+" does not exist in this poll");
				if(options.contains(option))
					throw new BadRequestException("option with id "+optID+" specified more than once");
				options.add(option);
			}

			int[] voteIDs=PostStorage.voteInPoll(self.id, poll.id, optionIDs);
			if(voteIDs==null)
				throw new UserErrorException("err_poll_already_voted");

			poll.numVoters++;
			for(PollOption opt:options)
				opt.numVotes++;


			post.poll=poll; // So the last vote time is as it was before the vote
			sendUpdateQuestionIfNeeded(post);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public int getPostIDForPoll(Poll poll){
		try{
			return PostStorage.getPostIdByPollId(poll.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public int createPoll(User self, @NotNull Actor owner, @NotNull String question, @NotNull List<String> options, boolean anonymous, boolean multipleChoice, @Nullable Instant endTime){
		try{
			ensureUserNotBlocked(self, owner);
			if(owner instanceof User user){
				context.getPrivacyController().enforceUserPrivacy(self, user, UserPrivacySettingKey.WALL_POSTING);
			}else if(owner instanceof Group group){
				context.getPrivacyController().enforceUserAccessToGroupContent(self, group);
				if(group.wallState==GroupFeatureState.DISABLED)
					throw new UserActionNotAllowedException();
				if(group.wallState==GroupFeatureState.ENABLED_CLOSED)
					context.getGroupsController().enforceUserAdminLevel(group, self, Group.AdminLevel.MODERATOR);
			}
			return PostStorage.createPoll(owner.getOwnerID(), question, options, anonymous, multipleChoice, endTime);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	// endregion

	public enum WallMode{
		ALL,
		OWNER,
		OTHERS
	}

	private record PostGuidInfo(int postID, Instant time){}
}
