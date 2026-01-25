package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 店铺类型服务类
 */
public interface IShopTypeService extends IService<ShopType> {

    List<ShopType> getShopTypeList();
}
