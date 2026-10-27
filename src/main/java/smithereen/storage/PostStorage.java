package smithereen.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import smithereen.Config;
import smithereen.Utils;
import smithereen.model.objects.StoredObject;
import smithereen.model.objects.Actor;
import smithereen.model.media.MediaFileRef;
import smithereen.controllers.WallController;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.model.Group;
import smithereen.model.PaginatedList;
import smithereen.model.Poll;
import smithereen.model.PollOption;
import smithereen.model.Post;
import smithereen.model.PostSource;
import smithereen.model.User;
import smithereen.model.UserInteractions;
import smithereen.model.feed.CommentsNewsfeedObjectType;
import smithereen.model.feed.NewsfeedEntry;
import smithereen.model.media.MediaFileRecord;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.storage.sql.SQLQueryBuilder;
import smithereen.storage.utils.Pair;
import smithereen.text.FormattedTextFormat;
import smithereen.util.BackgroundTaskRunner;
import smithereen.util.NamedMutexCollection;
import smithereen.util.UriBuilder;
import spark.utils.StringUtils;

public class PostStorage{
	private static final Logger LOG=LoggerFactory.getLogger(PostStorage.class);

	private static final NamedMutexCollection foreignPostUpdateLocks=new NamedMutexCollection();
	private static final NamedMutexCollection pollVoteLocks=new NamedMutexCollection();

	public static int createWallPost(int userID, int ownerUserID, int ownerGroupID, String text, String textSource, FormattedTextFormat sourceFormat, List<Integer> replyKey,
									 Set<User> mentionedUsers, String attachments, String contentWarning, int pollID, int repostOf, Post.Action action, EnumSet<Post.Flag> flags, String extra) throws SQLException{
		if(ownerUserID<=0 && ownerGroupID<=0)
			throw new IllegalArgumentException("Need either ownerUserID or ownerGroupID");

		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int id=new SQLQueryBuilder(conn)
					.insertInto("wall_posts")
					.value("author_id", userID)
					.value("owner_user_id", ownerUserID>0 ? ownerUserID : null)
					.value("owner_group_id", ownerGroupID>0 ? ownerGroupID : null)
					.value("text", text)
					.value("reply_key", Utils.serializeIntList(replyKey))
					.value("mentions", mentionedUsers.isEmpty() ? null : Utils.serializeIntArray(mentionedUsers.stream().mapToInt(u->u.id).toArray()))
					.value("attachments", attachments)
					.value("content_warning", contentWarning)
					.value("poll_id", pollID>0 ? pollID : null)
					.value("source", textSource)
					.value("source_format", sourceFormat)
					.value("repost_of", repostOf!=0 ? repostOf : null)
					.value("action", action)
					.value("flags", Utils.serializeEnumSet(flags))
					.value("extra", extra)
					.executeAndGetID();

			if(replyKey!=null && !replyKey.isEmpty()){
				new SQLQueryBuilder(conn)
						.update("wall_posts")
						.valueExpr("reply_count", "reply_count+1")
						.whereIn("id", replyKey)
						.executeNoResult();
				new SQLQueryBuilder(conn)
						.update("wall_posts")
						.valueExpr("immediate_reply_count", "immediate_reply_count+1")
						.where("id=?", replyKey.getLast())
						.executeNoResult();

				SQLQueryBuilder.prepareStatement(conn, "INSERT INTO newsfeed_comments (user_id, object_type, object_id) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE object_id=object_id", userID, 0, replyKey.getFirst()).execute();
				BackgroundTaskRunner.getInstance().submit(new UpdateCommentBookmarksRunnable(replyKey.getFirst()));
			}
			return id;
		}
	}

	public static void updateWallPost(int id, String text, String textSource, FormattedTextFormat sourceFormat, Set<User> mentionedUsers, String attachments, String contentWarning, int pollID) throws SQLException{
		new SQLQueryBuilder()
				.update("wall_posts")
				.value("text", text)
				.value("source", textSource)
				.value("source_format", sourceFormat)
				.value("mentions", mentionedUsers.isEmpty() ? null : Utils.serializeIntArray(mentionedUsers.stream().mapToInt(u->u.id).toArray()))
				.value("attachments", attachments)
				.value("content_warning", contentWarning)
				.value("poll_id", pollID>0 ? pollID : null)
				.valueExpr("updated_at", "CURRENT_TIMESTAMP()")
				.where("id=?", id)
				.executeNoResult();
	}

	public static void updateWallPostCW(int id, String contentWarning) throws SQLException{
		new SQLQueryBuilder()
				.update("wall_posts")
				.value("content_warning", contentWarning)
				.where("id=?", id)
				.executeNoResult();
	}

	public static void updateWallPostExtraFields(int id, String extra) throws SQLException{
		new SQLQueryBuilder()
				.update("wall_posts")
				.value("extra", extra)
				.where("id=?", id)
				.executeNoResult();
	}

	public static List<Post> getWallPosts(int ownerID, boolean isGroup, int minID, int maxID, int offset, int count, int[] total, WallController.WallMode mode, Set<Post.Privacy> allowedPrivacy) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt;
			String condition=switch(mode){
				case OWNER -> " AND owner_user_id=author_id";
				case ALL -> "";
				case OTHERS -> " AND owner_user_id<>author_id";
			};
			String ownerField=isGroup ? "owner_group_id" : "owner_user_id";
			if(allowedPrivacy.size()<Post.Privacy.values().length){
				condition+=" AND privacy IN ("+allowedPrivacy.stream().map(p->String.valueOf(p.ordinal())).collect(Collectors.joining(", "))+")";
			}
			if(total!=null){
				stmt=conn.prepareStatement("SELECT COUNT(*) FROM `wall_posts` WHERE `"+ownerField+"`=? AND `reply_key` IS NULL"+condition);
				stmt.setInt(1, ownerID);
				try(ResultSet res=stmt.executeQuery()){
					res.next();
					total[0]=res.getInt(1);
				}
			}
			if(minID>0){
				stmt=conn.prepareStatement("SELECT * FROM `wall_posts` WHERE `"+ownerField+"`=? AND `id`>? AND `reply_key` IS NULL"+condition+" ORDER BY created_at DESC LIMIT "+count);
				stmt.setInt(2, minID);
			}else if(maxID>0){
				stmt=conn.prepareStatement("SELECT * FROM `wall_posts` WHERE `"+ownerField+"`=? AND `id`<=? AND `reply_key` IS NULL"+condition+" ORDER BY created_at DESC LIMIT "+offset+","+count);
				stmt.setInt(2, maxID);
			}else{
				stmt=conn.prepareStatement("SELECT * FROM `wall_posts` WHERE `"+ownerField+"`=? AND `reply_key` IS NULL"+condition+" ORDER BY created_at DESC LIMIT "+offset+","+count);
			}
			stmt.setInt(1, ownerID);
			ArrayList<Post> posts=new ArrayList<>();
			try(ResultSet res=stmt.executeQuery()){
				while(res.next()){
					posts.add(Post.fromResultSet(res));
				}
			}
			postprocessPosts(posts);
			return posts;
		}
	}

	public static PaginatedList<Post> getWallComments(int ownerID, int offset, int count) throws SQLException{
		String ownerField=ownerID<0 ? "owner_group_id" : "owner_user_id";
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.count()
					.where(ownerField+"=? AND reply_key IS NOT NULL", Math.abs(ownerID))
					.executeAndGetInt();

			if(total==0)
				return PaginatedList.emptyList(count);

			List<Post> posts=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.allColumns()
					.where(ownerField+"=? AND reply_key IS NOT NULL", Math.abs(ownerID))
					.orderBy("id ASC")
					.limit(count, offset)
					.executeAsStream(Post::fromResultSet)
					.toList();
			return new PaginatedList<>(posts, total, offset, count);
		}
	}

	public static List<Post> getWallToWall(int userID, int otherUserID, int offset, int count, int[] total) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt;
			if(total!=null){
				stmt=conn.prepareStatement("SELECT COUNT(*) FROM wall_posts WHERE ((owner_user_id=? AND author_id=?) OR (owner_user_id=? AND author_id=?)) AND `reply_key` IS NULL");
				stmt.setInt(1, userID);
				stmt.setInt(2, otherUserID);
				stmt.setInt(3, otherUserID);
				stmt.setInt(4, userID);
				try(ResultSet res=stmt.executeQuery()){
					res.next();
					total[0]=res.getInt(1);
				}
			}
			stmt=conn.prepareStatement("SELECT * FROM wall_posts WHERE ((owner_user_id=? AND author_id=?) OR (owner_user_id=? AND author_id=?)) AND `reply_key` IS NULL ORDER BY created_at DESC LIMIT "+offset+","+count);
			stmt.setInt(1, userID);
			stmt.setInt(2, otherUserID);
			stmt.setInt(3, otherUserID);
			stmt.setInt(4, userID);
			ArrayList<Post> posts=new ArrayList<>();
			try(ResultSet res=stmt.executeQuery()){
				while(res.next()){
					posts.add(Post.fromResultSet(res));
				}
			}
			postprocessPosts(posts);
			return posts;
		}
	}

	public static @NotNull Post getPostOrThrow(int postID, boolean onlyLocal) throws SQLException{
		if(postID<=0)
			throw new ObjectNotFoundException("err_post_not_found");
		Post post=getPostByID(postID, false);
		if(post==null)
			throw new ObjectNotFoundException("err_post_not_found");
		return post;
	}

	public static Post getPostByID(int postID, boolean wantDeleted) throws SQLException{
		Post post=new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.allColumns()
				.where("id=?", postID)
				.executeAndGetSingleObject(Post::fromResultSet);
		if(post==null || (post.isDeleted() && !wantDeleted))
			return null;
		postprocessPosts(Set.of(post));
		return post;
	}

	public static Map<Integer, Post> getPostsByID(Collection<Integer> ids) throws SQLException{
		if(ids.isEmpty())
			return Map.of();
		Map<Integer, Post> posts=new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.allColumns()
				.whereIn("id", ids)
				.executeAsStream(Post::fromResultSet)
				.filter(p->!p.isDeleted())
				.collect(Collectors.toMap(p->p.id, Function.identity()));
		postprocessPosts(posts.values());
		return posts;
	}

	public static Map<Integer, int[]> getPostOwnerAndAuthorIDs(Collection<Integer> postIDs) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.columns("id", "owner_user_id", "owner_group_id", "author_id")
				.whereIn("id", postIDs)
				.executeAsStream(r->{
					int uid=r.getInt("owner_user_id");
					return new Pair<>(r.getInt("id"), new int[]{uid>0 ? uid : -r.getInt("owner_group_id"), r.getInt("author_id")});
				})
				.collect(Collectors.toMap(Pair::first, Pair::second));
	}

	public static void deletePost(int id) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			Post post=getPostByID(id, false);
			if(post==null)
				return;
			PreparedStatement stmt;
			boolean needFullyDelete=true;
			if(post.getReplyLevel()>0){
				stmt=conn.prepareStatement("SELECT COUNT(*) FROM wall_posts WHERE reply_key LIKE BINARY bin_prefix(?)");
				ArrayList<Integer> rk=new ArrayList<>(post.replyKey);
				rk.add(post.id);
				stmt.setBytes(1, Utils.serializeIntList(rk));
				try(ResultSet res=stmt.executeQuery()){
					res.next();
					needFullyDelete=res.getInt(1)==0;
				}
			}

			if(post.poll!=null && post.poll.ownerID==post.authorID){
				SQLQueryBuilder.prepareStatement(conn, "DELETE FROM polls WHERE id=?", post.poll.id).execute();
			}

			// Delete Mastodon-style reposts as well
			Set<Integer> reposts=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.columns("id")
					.where("repost_of=? AND (flags & 1)=1", id)
					.executeAndGetIntStream()
					.boxed()
					.collect(Collectors.toSet());
			if(!reposts.isEmpty()){
				new SQLQueryBuilder(conn)
						.deleteFrom("newsfeed")
						.whereIn("object_id", reposts)
						.andWhere("`type`=?", NewsfeedEntry.Type.POST)
						.executeNoResult();
				new SQLQueryBuilder(conn)
						.deleteFrom("wall_posts")
						.whereIn("id", reposts)
						.executeNoResult();
			}

			if(needFullyDelete){
				stmt=conn.prepareStatement("DELETE FROM `wall_posts` WHERE `id`=?");
				stmt.setInt(1, id);
				stmt.execute();
			}else{
				// (comments don't exist in the feed anyway)
				stmt=conn.prepareStatement("UPDATE wall_posts SET author_id=NULL, owner_user_id=NULL, owner_group_id=NULL, text=NULL, attachments=NULL, content_warning=NULL, updated_at=NULL, mentions=NULL, source=NULL WHERE id=?");
				stmt.setInt(1, id);
				stmt.execute();
			}
			stmt=conn.prepareStatement("DELETE FROM `newsfeed` WHERE (`type`=0 OR `type`=1) AND `object_id`=?");
			stmt.setInt(1, id);
			stmt.execute();
			new SQLQueryBuilder(conn)
					.deleteFrom("newsfeed_groups")
					.where("type=? AND object_id=?", NewsfeedEntry.Type.POST, id)
					.executeNoResult();

			if(post.getReplyLevel()>0){
				conn.createStatement().execute("UPDATE wall_posts SET reply_count=GREATEST(1, reply_count)-1 WHERE id IN ("+post.replyKey.stream().map(String::valueOf).collect(Collectors.joining(","))+")");
				new SQLQueryBuilder(conn)
						.update("wall_posts")
						.valueExpr("immediate_reply_count", "GREATEST(1, immediate_reply_count)-1")
						.where("id=?", post.replyKey.getLast())
						.executeNoResult();
				BackgroundTaskRunner.getInstance().submit(new UpdateCommentBookmarksRunnable(post.replyKey.get(0)));
			}else{
				BackgroundTaskRunner.getInstance().submit(new DeleteCommentBookmarksRunnable(id));
			}
		}
	}

	public static Map<Integer, PaginatedList<Post>> getRepliesForFeed(Set<List<Integer>> postReplyKeys, boolean flat, int count) throws SQLException{
		if(postReplyKeys.isEmpty())
			return Collections.emptyMap();
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=conn.prepareStatement(String.join(" UNION ALL ", Collections.nCopies(postReplyKeys.size(), "(SELECT *, ? AS `parent_id` FROM wall_posts WHERE reply_key"+(flat ? " LIKE BINARY bin_prefix(?)" : "=?")+" ORDER BY created_at DESC LIMIT "+count+")")));
			int i=0;
			for(List<Integer> id:postReplyKeys){
				stmt.setInt(i+1, id.getLast());
				stmt.setBytes(i+2, Utils.serializeIntList(id));
				i+=2;
			}
			LOG.debug("{}", stmt);
			HashMap<Integer, PaginatedList<Post>> map=new HashMap<>();
			try(ResultSet res=stmt.executeQuery()){
				while(res.next()){
					Post post=Post.fromResultSet(res);
					int parentID=res.getInt("parent_id");
					List<Post> posts=map.computeIfAbsent(parentID, (k)->new PaginatedList<>(new ArrayList<>(), 0)).list;
					posts.addFirst(post);
				}
			}
			postprocessPosts(map.values().stream().flatMap(l->l.list.stream()).toList());
			stmt=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.selectExpr("count(*), reply_key")
					.groupBy("reply_key")
					.whereIn("reply_key", postReplyKeys.stream().map(Utils::serializeIntList).collect(Collectors.toList()))
					.createStatement();
			try(ResultSet res=stmt.executeQuery()){
				while(res.next()){
					int id=Utils.deserializeIntList(res.getBytes(2)).getLast();
					map.get(id).total=res.getInt(1);
				}
			}
			return map;
		}
	}

	public static ThreadedReplies getRepliesThreaded(List<Integer> prefix, int topLevelOffset, int topLevelLimit, int secondaryLimit, boolean twoLevel, boolean reversed) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){

			byte[] serializedPrefix=Utils.serializeIntList(prefix);

			int total=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.count()
					.where("reply_key=?", (Object) serializedPrefix)
					.executeAndGetInt();

			if(total==0)
				return new ThreadedReplies(List.of(), List.of(), 0);

			List<Post> posts=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.allColumns()
					.where("reply_key=?", (Object) serializedPrefix)
					.limit(topLevelLimit, topLevelOffset)
					.orderBy("created_at "+(reversed ? "DESC" : "ASC"))
					.executeAsStream(Post::fromResultSet)
					.collect(Collectors.toList());
			postprocessPosts(posts);

			ArrayList<String> wheres=new ArrayList<>();
			ArrayList<Object> whereArgs=new ArrayList<>();
			for(Post post:posts){
				if(post.replyCount>0){
					wheres.add("reply_key LIKE BINARY bin_prefix(?)");
					whereArgs.add(Utils.serializeIntList(post.getReplyKeyForReplies()));
				}
			}

			List<Post> replies;
			if(!whereArgs.isEmpty()){
				whereArgs.add(secondaryLimit);
				PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT * FROM wall_posts WHERE "+String.join(" OR ", wheres)+" ORDER BY created_at ASC"+(twoLevel ? "" : ", LENGTH(reply_key) ASC")+" LIMIT ?",
						whereArgs.toArray());
				replies=DatabaseUtils.resultSetToObjectStream(stmt.executeQuery(), Post::fromResultSet, null).toList();
				postprocessPosts(replies);
			}else{
				replies=List.of();
			}

			return new ThreadedReplies(posts, replies, total);
		}
	}

	public static PaginatedList<Post> getRepliesFlat(List<Integer> prefix, int offset, int limit, boolean reversed) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			byte[] serializedPrefix=Utils.serializeIntList(prefix);

			int total=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.count()
					.where("reply_key LIKE BINARY bin_prefix(?) AND author_id IS NOT NULL", (Object) serializedPrefix)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(limit);

			List<Post> list=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.allColumns()
					.where("reply_key LIKE BINARY bin_prefix(?) AND author_id IS NOT NULL", (Object) serializedPrefix)
					.limit(limit, offset)
					.orderBy("created_at "+(reversed ? "DESC" : "ASC"))
					.executeAsStream(Post::fromResultSet)
					.toList();
			postprocessPosts(list);
			return new PaginatedList<>(list, total, offset, limit);
		}
	}

	public static PaginatedList<Post> getRepliesFlatWithMaxID(List<Integer> prefix, int maxID, int limit) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			byte[] serializedPrefix=Utils.serializeIntList(prefix);

			int total=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.count()
					.where("reply_key LIKE BINARY bin_prefix(?) AND created_at<=(SELECT `created_at` FROM `wall_posts` WHERE id=?) AND id<>?", (Object) serializedPrefix, maxID, maxID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(limit);

			List<Post> list=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.allColumns()
					.where("reply_key LIKE BINARY bin_prefix(?) AND created_at<=(SELECT `created_at` FROM `wall_posts` WHERE id=?) AND id<>?", (Object) serializedPrefix, maxID, maxID)
					.limit(limit, 0)
					.orderBy("created_at DESC")
					.executeAsStream(Post::fromResultSet)
					.toList()
					.reversed();
			postprocessPosts(list);
			return new PaginatedList<>(list, total, 0, limit);
		}
	}

	public static PaginatedList<Post> getRepliesExact(int[] replyKey, int maxID, int limit) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.count()
					.where("reply_key=? AND created_at<=(SELECT `created_at` FROM `wall_posts` WHERE id=?) AND id<>?", Utils.serializeIntArray(replyKey), maxID, maxID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(limit);

			List<Post> posts=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.allColumns()
					.where("reply_key=? AND created_at<=(SELECT `created_at` FROM `wall_posts` WHERE id=?) AND id<>?", Utils.serializeIntArray(replyKey), maxID, maxID)
					.limit(limit, 0)
					.orderBy("created_at ASC")
					.executeAsStream(Post::fromResultSet)
					.toList();
			postprocessPosts(posts);
			return new PaginatedList<>(posts, total, 0, limit);
		}
	}

	public static int getLocalPostCount(boolean comments) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.count()
				.where("reply_key IS "+(comments ? "NOT " : "")+"NULL")
				.executeAndGetInt();
	}

	public static HashMap<Integer, UserInteractions> getPostInteractions(Collection<Integer> postIDs, int userID) throws SQLException{
		HashMap<Integer, UserInteractions> result=new HashMap<>();
		if(postIDs.isEmpty())
			return result;
		for(int id:postIDs)
			result.put(id, new UserInteractions());
		String idsStr=postIDs.stream().map(Object::toString).collect(Collectors.joining(","));

		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			try(ResultSet res=conn.createStatement().executeQuery("SELECT object_id, COUNT(*) FROM likes WHERE object_type=0 AND object_id IN ("+idsStr+") GROUP BY object_id")){
				if(res.next()){
					do{
						result.get(res.getInt(1)).likeCount=res.getInt(2);
					}while(res.next());
				}
			}
			if(userID!=0){
				PreparedStatement stmt=conn.prepareStatement("SELECT object_id FROM likes WHERE object_type=0 AND object_id IN ("+idsStr+") AND user_id=?");
				stmt.setInt(1, userID);
				try(ResultSet res=stmt.executeQuery()){
					while(res.next()){
						result.get(res.getInt(1)).isLiked=true;
					}
				}
			}

			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT option_id FROM poll_votes WHERE user_id=? AND poll_id=?", userID);
			try(ResultSet res=conn.createStatement().executeQuery("SELECT id, reply_count, poll_id FROM wall_posts WHERE id IN ("+idsStr+")")){
				while(res.next()){
					UserInteractions interactions=result.get(res.getInt(1));
					interactions.commentCount=res.getInt(2);
					if(userID!=0){
						int pollID=res.getInt(3);
						if(!res.wasNull()){
							stmt.setInt(2, pollID);
							try(ResultSet res2=stmt.executeQuery()){
								interactions.pollChoices=new ArrayList<>();
								while(res2.next()){
									interactions.pollChoices.add(res2.getInt(1));
								}
							}
						}
					}
				}
			}

			String userRepostedColumn=userID==0 ? "" : (", MAX(owner_user_id="+userID+")");
			try(ResultSet res=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.selectExpr("repost_of, COUNT(*)"+userRepostedColumn)
					.whereIn("repost_of", postIDs)
					.groupBy("repost_of")
					.execute()){
				while(res.next()){
					UserInteractions interactions=result.get(res.getInt(1));
					interactions.repostCount=res.getInt(2);
					if(userID>0)
						interactions.isReposted=res.getBoolean(3);
				}
			}
		}

		return result;
	}

	public static Poll getPoll(int id) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			Poll poll=new SQLQueryBuilder(conn)
					.selectFrom("polls")
					.allColumns()
					.where("id=?", id)
					.executeAndGetSingleObject(Poll::fromResultSet);
			if(poll==null)
				return null;
			new SQLQueryBuilder(conn)
					.selectFrom("poll_options")
					.where("poll_id=?", id)
					.executeAsStream(res->PollOption.fromResultSet(res, poll))
					.forEach(poll.options::add);
			return poll;
		}
	}

	public static int[] voteInPoll(int userID, int pollID, int[] optionIDs) throws SQLException{
		String key=userID+"|"+pollID;
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			pollVoteLocks.acquire(key);
			int count=new SQLQueryBuilder(conn)
					.selectFrom("poll_votes")
					.count()
					.where("user_id=? AND poll_id=?", userID, pollID)
					.executeAndGetInt();
			if(count>0)
				return null;

			PreparedStatement stmt1=new SQLQueryBuilder(conn)
					.insertInto("poll_votes")
					.value("option_id", 0)
					.value("user_id", userID)
					.value("poll_id", pollID)
					.createStatement(Statement.RETURN_GENERATED_KEYS);

			PreparedStatement stmt2=new SQLQueryBuilder(conn)
					.update("poll_options")
					.where("id=?", 0)
					.valueExpr("num_votes", "num_votes+1")
					.createStatement();

			int[] voteIDs=new int[optionIDs.length];
			int i=0;
			for(int optID: optionIDs){
				stmt1.setInt(1, optID);
				stmt1.execute();
				stmt2.setInt(1, optID);
				stmt2.execute();
				try(ResultSet res=stmt1.getGeneratedKeys()){
					res.next();
					voteIDs[i++]=res.getInt(1);
				}
			}
			stmt1.close();
			stmt2.close();

			new SQLQueryBuilder(conn)
					.update("polls")
					.valueExpr("num_voted_users", "num_voted_users+1")
					.valueExpr("last_vote_time", "CURRENT_TIMESTAMP()")
					.where("id=?", pollID)
					.executeNoResult();

			return voteIDs;
		}finally{
			pollVoteLocks.release(key);
		}
	}

	// This is called once for each choice in a multiple-choice poll
	public static int voteInPoll(int userID, int pollID, int optionID, boolean allowMultiple) throws SQLException{
		String key=userID+"|"+pollID;
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			pollVoteLocks.acquire(key);
			PreparedStatement stmt=new SQLQueryBuilder(conn)
					.selectFrom("poll_votes")
					.columns("option_id")
					.where("user_id=? AND poll_id=?", userID, pollID)
					.createStatement();
			boolean userVoted=false;
			try(ResultSet res=stmt.executeQuery()){
				while(res.next()){
					// this is a single-choice poll and there's already a vote
					if(!allowMultiple)
						return 0;
					userVoted=true;
					int optID=res.getInt(1);
					if(optID==optionID)
						return 0;
				}
			}

			int rVoteID=new SQLQueryBuilder(conn)
					.insertInto("poll_votes")
					.value("option_id", optionID)
					.value("user_id", userID)
					.value("poll_id", pollID)
					.executeAndGetID();

			new SQLQueryBuilder(conn)
					.update("poll_options")
					.where("id=?", optionID)
					.valueExpr("num_votes", "num_votes+1")
					.executeNoResult();

			if(!userVoted){
				new SQLQueryBuilder(conn)
						.update("polls")
						.valueExpr("num_voted_users", "num_voted_users+1")
						.where("id=?", pollID)
						.executeNoResult();
			}

			return rVoteID;
		}finally{
			pollVoteLocks.release(key);
		}
	}

	public static int createPoll(int ownerID, @NotNull String question, @NotNull List<String> options, boolean anonymous, boolean multiChoice, @Nullable Instant endTime) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int pollID=new SQLQueryBuilder(conn)
					.insertInto("polls")
					.value("owner_id", ownerID)
					.value("question", question)
					.value("is_anonymous", anonymous)
					.value("is_multi_choice", multiChoice)
					.value("end_time", endTime)
					.executeAndGetID();

			for(String opt:options){
				new SQLQueryBuilder(conn)
						.insertInto("poll_options")
						.value("text", opt)
						.value("poll_id", pollID)
						.executeNoResult();
			}

			return pollID;
		}
	}

	public static void deletePoll(int pollID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("polls")
				.where("id=?", pollID)
				.executeNoResult();
	}

	public static List<Integer> getPollOptionVoters(int optionID, int offset, int count) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("poll_votes")
				.columns("user_id")
				.where("option_id=?", optionID)
				.orderBy("id ASC")
				.limit(count, offset)
				.executeAndGetIntList();
	}

	public static PaginatedList<NewsfeedEntry> getCommentsFeed(int userID, int offset, int count, EnumSet<CommentsNewsfeedObjectType> filter) throws SQLException{
		if(filter.isEmpty())
			return PaginatedList.emptyList(count);
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("newsfeed_comments")
					.count()
					.whereIn("object_type", filter)
					.andWhere("user_id=?", userID)
					.executeAndGetInt();

			if(total==0)
				return new PaginatedList<>(Collections.emptyList(), 0);

			List<NewsfeedEntry> entries=new SQLQueryBuilder(conn)
					.selectFrom("newsfeed_comments")
					.columns("object_type", "object_id")
					.whereIn("object_type", filter)
					.andWhere("user_id=?", userID)
					.orderBy("last_comment_time DESC")
					.limit(count, offset)
					.executeAsStream(res->{
						CommentsNewsfeedObjectType type=CommentsNewsfeedObjectType.values()[res.getInt(1)];
						NewsfeedEntry entry=new NewsfeedEntry();
						entry.objectID=res.getLong(2);
						entry.type=switch(type){
							case POST -> NewsfeedEntry.Type.POST;
							case PHOTO -> NewsfeedEntry.Type.PHOTO;
							case BOARD_TOPIC -> NewsfeedEntry.Type.BOARD_TOPIC;
						};
						return entry;
					}).toList();

			return new PaginatedList<>(entries, total, offset, count);
		}
	}

	/**
	 * Everyone who has the object in their comments feed, i.e. has commented on it.
	 */
	public static List<Integer> getCommentsFeedParticipants(CommentsNewsfeedObjectType type, long objectID) throws SQLException {
		return new SQLQueryBuilder()
				.selectFrom("newsfeed_comments")
				.columns("user_id")
				.where("object_type=? AND object_id=?", type, objectID)
				.executeAndGetIntList();
	}

	public static int getPostIdByPollId(int pollID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.columns("id")
				.where("poll_id=?", pollID)
				.executeAndGetInt();
	}

	public static PostSource getPostSource(int id) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.columns("source", "source_format")
				.where("id=?", id)
				.executeAndGetSingleObject(r->new PostSource(r.getString(1), FormattedTextFormat.values()[r.getInt(2)]));
	}

	public static int getUserPostCount(int id) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.count()
				.where("owner_user_id=? AND author_id=owner_user_id AND reply_key IS NULL", id)
				.executeAndGetInt();
	}

	public static int getUserPostCommentCount(int id) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.count()
				.where("author_id=? AND reply_key IS NOT NULL", id)
				.executeAndGetInt();
	}

	private static void postprocessPosts(Collection<Post> posts) throws SQLException{
		MediaFileResolver.resolveAttachments(posts);
	}

	public static List<Integer> getRepostedUsers(int postID, int count) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.columns("author_id")
				.where("repost_of=?", postID)
				.orderBy("MAX(created_at) DESC")
				.groupBy("author_id")
				.limit(count, 0)
				.executeAndGetIntList();
	}

	public static PaginatedList<Post> getPostReposts(int postID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.count()
					.where("repost_of=?", postID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			List<Post> posts=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.allColumns()
					.where("repost_of=?", postID)
					.orderBy("created_at DESC")
					.limit(count, offset)
					.executeAsStream(Post::fromResultSet)
					.toList();
			return new PaginatedList<>(posts, total, offset, count);
		}
	}

	public static Map<Integer, Integer> getPostAuthors(Collection<Integer> ids) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.columns("id", "author_id")
				.whereIn("id", ids)
				.executeAsStream(res->new Pair<Integer, Integer>(res.getInt("id"), res.getInt("author_id")))
				.collect(Collectors.toMap(Pair::first, Pair::second));
	}

	public static List<Post> getUserReplies(int userID, Collection<List<Integer>> replyKeys) throws SQLException{
		List<Post> posts=new SQLQueryBuilder()
				.selectFrom("wall_posts")
				.allColumns()
				.whereIn("reply_key", replyKeys.stream().map(Utils::serializeIntList).toList())
				.andWhere("author_id=?", userID)
				.executeAsStream(Post::fromResultSet)
				.toList();
		postprocessPosts(posts);
		return posts;
	}

	public static PaginatedList<Post> getAllPostsByAuthor(int userID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.count()
					.where("author_id=?", userID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);

			List<Post> posts=new SQLQueryBuilder(conn)
					.selectFrom("wall_posts")
					.where("author_id=?", userID)
					.orderBy("created_at DESC")
					.limit(count, offset)
					.executeAsStream(Post::fromResultSet)
					.toList();
			postprocessPosts(posts);
			return new PaginatedList<>(posts, total, offset, count);
		}
	}

	public static void recountComments(int ownerID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			LOG.trace("Started recounting wall comments for {}", ownerID);

			int batchSize=1000;
			int offset=0;
			while(true){
				try(ResultSet res=new SQLQueryBuilder(conn)
						.selectFrom("wall_posts")
						.columns("id", "reply_key", "reply_count")
						.where(ownerID>0 ? "owner_user_id=?" : "owner_group_id=?", Math.abs(ownerID))
						.orderBy("id ASC")
						.limit(batchSize, offset)
						.execute()){
					if(!res.next())
						break;

					do{
						int id=res.getInt("id");
						ArrayList<Integer> replyKey=new ArrayList<>(Utils.deserializeIntList(res.getBytes("reply_key")));
						replyKey.add(id);
						int count=new SQLQueryBuilder(conn)
								.selectFrom("wall_posts")
								.count()
								.where("reply_key LIKE BINARY bin_prefix(?)", (Object)Utils.serializeIntList(replyKey))
								.executeAndGetInt();
						if(count!=res.getInt("reply_count")){
							new SQLQueryBuilder(conn)
									.update("wall_posts")
									.where("id=?", id)
									.value("reply_count", count)
									.executeNoResult();
						}
					}while(res.next());

					offset+=batchSize;
				}
			}

			LOG.trace("Done recounting wall comments for {}", ownerID);
		}
	}

	// region Pinned posts

	public static void pinPost(int ownerID, int postID, boolean keepPrevious) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int order;
			if(!keepPrevious){
				new SQLQueryBuilder(conn)
						.deleteFrom("wall_pinned_posts")
						.where("owner_user_id=?", ownerID)
						.executeNoResult();
				order=0;
			}else{
				order=new SQLQueryBuilder(conn)
						.selectFrom("wall_pinned_posts")
						.selectExpr("MAX(display_order)")
						.where("owner_user_id=?", ownerID)
						.executeAndGetInt()+1;
			}
			new SQLQueryBuilder(conn)
					.insertIgnoreInto("wall_pinned_posts")
					.value("owner_user_id", ownerID)
					.value("post_id", postID)
					.value("display_order", order)
					.executeNoResult();

			if(!keepPrevious){
				int count=new SQLQueryBuilder(conn)
						.selectFrom("wall_pinned_posts")
						.count()
						.where("owner_user_id=?", ownerID)
						.executeAndGetInt();
				if(count>WallController.MAX_PINNED_POSTS){
					Set<Integer> toDelete=new SQLQueryBuilder(conn)
							.selectFrom("wall_pinned_posts")
							.columns("post_id")
							.where("owner_user_id=?", ownerID)
							.limit(count-WallController.MAX_PINNED_POSTS, 0)
							.orderBy("display_order ASC")
							.executeAndGetIntStream()
							.boxed()
							.collect(Collectors.toSet());
					new SQLQueryBuilder(conn)
							.deleteFrom("wall_pinned_posts")
							.whereIn("post_id", toDelete)
							.andWhere("owner_user_id=?", ownerID)
							.executeNoResult();
				}
			}
		}
	}

	public static void unpinPost(int ownerID, int postID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("wall_pinned_posts")
				.where("owner_user_id=? AND post_id=?", ownerID, postID)
				.executeNoResult();
	}

	public static List<Integer> getPinnedPostIDs(int ownerID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_pinned_posts")
				.columns("post_id")
				.where("owner_user_id=?", ownerID)
				.orderBy("display_order DESC")
				.executeAndGetIntStream()
				.boxed()
				.toList();
	}

	public static boolean isPostPinned(int id) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("wall_pinned_posts")
				.count()
				.where("post_id=?", id)
				.executeAndGetInt()>0;
	}

	public static void clearPinnedPosts(int userID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("wall_pinned_posts")
				.where("owner_user_id=?", userID)
				.executeNoResult();
	}

	// endregion

	private record DeleteCommentBookmarksRunnable(int postID) implements Runnable{
		@Override
		public void run(){
			try{
				new SQLQueryBuilder()
						.deleteFrom("newsfeed_comments")
						.where("object_type=? AND object_id=?", 0, postID)
						.executeNoResult();
			}catch(SQLException x){
				LOG.warn("Error deleting comment bookmarks for post {}", postID, x);
			}
		}
	}

	private record UpdateCommentBookmarksRunnable(int postID) implements Runnable{
		@Override
		public void run(){
			try{
				try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
					PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT MAX(created_at) FROM wall_posts WHERE reply_key LIKE BINARY bin_prefix(?)", (Object) Utils.serializeIntArray(new int[]{postID}));
					Timestamp ts;
					try(ResultSet res=stmt.executeQuery()){
						res.next();
						ts=res.getTimestamp(1);
					}
					if(ts==null){
						new SQLQueryBuilder(conn)
								.deleteFrom("newsfeed_comments")
								.where("object_type=? AND object_id=?", CommentsNewsfeedObjectType.POST, postID)
								.executeNoResult();
					}else{
						new SQLQueryBuilder(conn)
								.update("newsfeed_comments")
								.value("last_comment_time", ts)
								.where("object_type=? AND object_id=?", CommentsNewsfeedObjectType.POST, postID)
								.executeNoResult();
					}
				}
			}catch(SQLException x){
				LOG.warn("Error updating comment bookmarks for post {}", postID, x);
			}
		}
	}

	public record ThreadedReplies(List<Post> posts, List<Post> replies, int total){}
}
