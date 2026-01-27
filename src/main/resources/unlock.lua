--基于redis实现的分布式锁
--使用lua脚本把判断锁和释放锁操作封装，保证原子性
--比较线程标识与锁中的标识是否一致？
if(redis.call('get',KEYS[1]) == ARGV[1]) then
   ---释放锁
   return redis.call('del',KEYS[1])
end
return 0