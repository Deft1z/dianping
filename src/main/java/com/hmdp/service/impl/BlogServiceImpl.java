package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 *  博客 服务实现类
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private UserServiceImpl userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查看热门博客
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户点赞数量查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据 需要封装一些属性
        List<Blog> records = page.getRecords();
        records.forEach(blog ->{
            //加上发布用户的头像和昵称两个字段
            queryBlogUser(blog);
            //查询当前用户是否点赞
            isliked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 点赞博客
     */
    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户
        Long user_id = UserHolder.getUser().getId();
        //2.判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key,user_id.toString());
        //3.如果不存在 未点赞 可以点赞
        if(score == null){
            //3.1数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id",id).update();
            //3.2保存用户到Redis的Zset集合 zadd key value score
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,user_id.toString(),System.currentTimeMillis());
            }
        }else{
            //4.如果已经点赞 则 取消点赞
            //4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked-1").eq("id",id).update();
            //4.2把用户从Redis的Zset集合中删除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,user_id.toString());
            }
        }
        return Result.ok("点赞成功！");
    }

    /**
     * 点赞排名榜（TOP5个点赞的用户）
     */
    @Override
    public Result queryBlogTop5Likes(Long id) {
        //1.在Zset中查询top5的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key,0,4);
        if(top5 == null||top5.isEmpty()){
            //如果查询为空 返回一个空集合
            return Result.ok(Collections.emptyList());
        }

        List<Long>ids = new ArrayList<>();
        //2.解析中其中的用户ID
//        for(String str:top5){
//            LongValue longValue = new LongValue(str);
//            ids.add(longValue.getValue());
//        }
        //更简洁的写法
        ids = top5
                .stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        //3.根据id查询用户对象
        String strids = StrUtil.join(",",ids);
        //List<User> users = userService.listByIds(ids);

        // 根据id排序 select * from tb_user where id in(5,1) order by field(id, 5, 1)
        List<UserDTO> userDTOList = userService.list(new LambdaQueryWrapper<User>()
                        .in(User::getId, ids)
                        .last("order by field (id," + strids + ")"))
                        .stream()
                        .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                        .collect(Collectors.toList());

        //4.返回
        return Result.ok(userDTOList);
    }

    /**
     * 发布博客并推送给关注
     * @param blog
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        Boolean isSuccess = save(blog);
        // 3.推模式实现关注推送:查询该用户的所有粉丝 select * from follow where follow_user_id = user.id
        if(isSuccess) {
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getFollowUserId, user.getId());
            List<Follow>follows  = followService.list(queryWrapper);
            //4.推送给所有粉丝
            for(Follow follow:follows){
                //4.1 获取粉丝ID
                Long userId = follow.getUserId();
                //4.2 推送 blog_id + 发布时间
                String key = "feed:"+userId;
                stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
            }
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }


    /**
     * 查看通过推模式推送的关注博客
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱  Zrevrangebyscore key max min withscores limit (offset:跳过与上一次查询最小值相同的数) (count 查几个)
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //4.解析数据 得到blog.id列表 minTime(时间戳） offset偏移量（和最小值一样的个数）
        List<Long>ids = new ArrayList<>();//获取所有blog_id
        Long minTime = 0L;//获取最小时间
        int mincnt = 1;
        for(ZSetOperations.TypedTuple<String>typedTuple:typedTuples){
            //4.1 获取blog_id
            String idstr = typedTuple.getValue();
            ids.add(new LongValue(idstr).getValue());
            //4.2 统计offset [最小值的个数]
            Long time = typedTuple.getScore().longValue();
            if(minTime.equals(time)){
                ++mincnt;
            }else {
                //4.3 找到了更小的时间戳 更新最小时间戳
                minTime = time;
                mincnt = 1;
            }
        }
        //5.根据blog_id获取blog
        //直接使用list(ids)会出现问题 mysql的in会打乱顺序 需要添加一个排序条件
        String idStr = StrUtil.join(",",ids);
        List<Blog>blogs = query()
                .in("id",ids)
                .last("ORDER BY FIELD(id,"+idStr+")").list();
        //填充每一个blog的其他字段 发布用户和点赞
        for(Blog blog:blogs){
            queryBlogUser(blog);
            isliked(blog);
        }
        //6.封装 返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(mincnt);
        return Result.ok(r);
    }

    /**
     * 根据ID查询博客
     */
    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        //2.查询发布该博客的用户的头像和昵称
        queryBlogUser(blog);
        //3.查询当前用户是否点赞
        isliked(blog);
        //4.返回结果
        return Result.ok(blog);
    }

    /**
     * 封装博客是否点赞过的字段isLike
     */
    private void isliked(Blog blog) {
        //获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            //用户未登录不需要检查有无点赞
            return ;
        }
        //1.获取登录用户ID
        Long user_id = UserHolder.getUser().getId();
        //2.判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key,user_id.toString());
        //3.设置islike字段
        blog.setIsLike(score != null);
    }

    /**
     * 封装发布该博客的用户头像和名字
     */
    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
