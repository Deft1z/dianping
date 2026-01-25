package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IUserService userService;

    /**
     * 发布评论
     */
    @Override
    public Result saveBlogComment(BlogComments blogComments, Long blogId) {
        //1.获取当前登录用户
        UserDTO user = UserHolder.getUser();
        blogComments.setUserId(user.getId());
        //2.保存评论
        blogComments.setBlogId(blogId);
        this.save(blogComments);
        //3.返回
        return Result.ok(blogId);
    }

    /**
     * 获取所有评论
     */
    @Override
    public Result getBlogComments(Long blogId) {
        LambdaQueryWrapper<BlogComments>queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(BlogComments::getBlogId,blogId);
        List<BlogComments> list = this.list(queryWrapper);
        list.forEach(this::queryBlogCommentUser);
        return Result.ok(list);
    }

    /**
     * 封装用户头像和名字
     * @param blogComments
     */
    private void queryBlogCommentUser(BlogComments blogComments){
        Long userId = blogComments.getUserId();
        User user = userService.getById(userId);
        blogComments.setName(user.getNickName());
        blogComments.setIcon(user.getIcon());
    }
}
