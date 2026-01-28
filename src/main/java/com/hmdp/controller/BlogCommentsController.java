package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IUserService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 *  评论 Controller
 *
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;


    /**
     * 发布评论
     */
    @PostMapping("/{id}")
    public Result saveBlogComments(@RequestBody BlogComments blogComments,@PathVariable("id") Long blogId) {
        return blogCommentsService.saveBlogComment(blogComments,blogId);
    }


    /**
     * 获取某条博客的所有评论
     */
    @GetMapping("/{id}")
    public Result getBlogComments(@PathVariable("id") Long blogId){
        return blogCommentsService.getBlogComments(blogId);
    }


}
