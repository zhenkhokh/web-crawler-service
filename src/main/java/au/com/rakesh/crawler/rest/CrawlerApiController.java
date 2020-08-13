package au.com.rakesh.crawler.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.swing.text.html.Option;
import javax.validation.constraints.NotNull;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import au.com.rakesh.crawler.configuration.AppProperties.CrawlerProperties;
import au.com.rakesh.crawler.model.PageTreeInfo;
import au.com.rakesh.crawler.service.CrawlerService;
import lombok.extern.slf4j.Slf4j;

@javax.annotation.Generated(value = "io.swagger.codegen.languages.SpringCodegen", date = "2017-09-02T22:39:21.740Z")

@Controller
@Slf4j
public class CrawlerApiController implements CrawlerApi {

    @Inject
    private CrawlerService crawlerService;

    @Value("#{appProperties.crawler}")
    private CrawlerProperties crawlerProperties;

    @Override
    @GetMapping(value = "/crawler", produces = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<PageTreeInfo> getWebPageTreeInfo(
            @NotNull @RequestParam(value = "url", required = true) final String url,
            @RequestParam(value = "depth", required = false) final Integer depth) {

        log.info("Request for deep crawling received for url: {}, depth: {}", url, depth);
        final int newDepth = Integer.min(Optional.ofNullable(depth).orElse(crawlerProperties.getDefaultDepth()),
                crawlerProperties.getMaxDepthAllowed());
        log.info(
                "Depth might be optimized to go upto Max defined in property:'app.crawler.max-depth-allowed'. optimized depth: {}",
                newDepth);
        int offset = 10;
        PageTreeInfo pageTreeInfo = crawlerService.deepCrawl(url, 1, null, true);
        PageTreeInfo pageTreeInfoPrev = pageTreeInfo;
        boolean isNextPage = pageTreeInfo != null;
        while (isNextPage) {
            String urlParams = String.format("?citingPapersSort=relevance&citingPapersLimit=10&citingPapersOffset=%d"
                    , offset);
            PageTreeInfo pageTreeInfo_ = crawlerService.deepCrawl(url + urlParams, 1, null, true);
            isNextPage = false;
            if (pageTreeInfo_!=null) {
                pageTreeInfo_.setNodes(pageTreeInfo_.getNodes().stream()
                        .filter(a -> !a.getUrl().contains(url))
                        .flatMap(a -> {
                            a.setUrl(a.getUrl().replace(urlParams, ""));
                            return Stream.of(a);
                        }).collect(Collectors.toList()));
                pageTreeInfo.getNodes().addAll(pageTreeInfo_.getNodes()
                        .stream().filter(node->!pageTreeInfo.getNodes().contains(node)).collect(Collectors.toList()));
                offset = offset + 10;
                isNextPage = !pageTreeInfoPrev.getNodes().equals(pageTreeInfo_.getNodes());
                pageTreeInfoPrev = pageTreeInfo_;
            }
        }
        final List<PageTreeInfo> excluded = new ArrayList(pageTreeInfoPrev !=null && pageTreeInfoPrev.getNodes()!=null
                ?pageTreeInfoPrev.getNodes():new ArrayList());
        List topThree = excluded.stream()
                .filter(a -> !a.getUrl().contains("#"))
                .filter(a -> !a.getUrl().contains(url))
                .filter(a -> !a.getTitle().matches("Figure \\d+ from .*"))
                .collect(Collectors.toList());
        if (topThree.size()>=3)
            topThree = topThree.subList(0,3);
        pageTreeInfo.setNodes(pageTreeInfo.getNodes().stream()
                .filter(node -> {
                    Optional topCited =  excluded.stream().filter(e->node.equals(e)).findFirst();
//                    if (topCited.isPresent())
//                        excluded.remove(topCited.get());
                    return !topCited.isPresent();
                }).filter(a -> !a.getUrl().contains(url))
                .collect(Collectors.toList()));
        pageTreeInfo.getNodes().addAll(0, topThree);
//        int n=0;
        if (depth>1)
            for (PageTreeInfo node: pageTreeInfo.getNodes()) {
                ResponseEntity<PageTreeInfo> deepResponde = getWebPageTreeInfo(node.getUrl(),depth-1);
                node.addNodesItem(deepResponde.getBody());
//                if (++n>0)
//                    break;
            }
        return new ResponseEntity<>(pageTreeInfo, HttpStatus.OK);
    }
}
