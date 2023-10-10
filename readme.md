# telegram-echo-example-bot

参考了ktor的一些设计思路。<br/> ~~基本上抄了一大半~~<br/>
不得不说kotlin官方的设计理念非常好，DSL的设计非常的优雅，可读性非常棒，就是容易写成六亲不认的样子。

使用的是kotlin-telegram-bot依赖。

~~这个bot的日志模块我没看懂~~，我只能再添加一个日志模块进去了。
它的日志交给了okHttp3进行处理，我就使用slf4j吧。


## 遇到过的问题

### 莫名其妙的依赖启动
像这种手动启动依赖的好处在于，我不会遇到莫名其妙的依赖自动启动。

(并且声明一下启动依赖的配置基本上也不会太难，都是一次写完然后就一直使用。)

在Spring应用程序中，官方专门给@SpringBootApplication注解中添加了忽略启动依赖的属性。就是专门防止依赖自启动。
我编写代码的时候，就遇到过这个问题，我压根没有引入GSON，但它给我报错GSON装配失败。后来查出来是一个依赖包中出现的GSON依赖，而Spring又刚好扫描到了。
往注解上添加忽略属性才解决这个问题。

自动装配确实是好东西，但是，自动装配一些我所不需要的依赖，就是一个大问题。

像ktor这样的手动设置依赖，我很喜欢。它能准确的告知我装配了什么依赖，而不是像SpringBoot那样，在某一次依赖包大更新后，莫名其妙的出现许多自动装配，
那些自动装配应该是它所属的依赖包内部使用的，而不是我们所使用的，我们并不需要它。
