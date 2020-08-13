package au.com.rakesh.crawler.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.cache.annotation.CacheResult;
import javax.inject.Named;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;

import au.com.rakesh.crawler.configuration.AppProperties.CrawlerProperties;
import au.com.rakesh.crawler.model.PageInfo;
import au.com.rakesh.crawler.model.PageTreeInfo;
import au.com.rakesh.crawler.service.CrawlerService;
import lombok.extern.slf4j.Slf4j;

/**
 * @author buddy
 */
@Slf4j
@Named
public class CrawlerServiceImpl implements CrawlerService {

    @Value("#{appProperties.crawler}")
    private CrawlerProperties crawlerProperties;
    private String mainTitle;

    /*
     * recursive crawler to fetch child pages upto desired depth / max depth
     * (non-Javadoc)
     *
     * @see au.com.rakesh.crawler.service.CrawlerService#deepCrawl(java.lang.String,
     * int)
     */
    @Override
    @CacheResult(cacheName = "web-crawler-service")
    public PageTreeInfo deepCrawl(final String url, final int depth, final List<String> processedUrls, boolean first) {

        log.debug("Starting crawler for url {} for depth {}", url, depth);
        if (depth < 0) {
            log.info("Maximum depth reached, backing out for url {}", url);
            return null;
        } else {
            final List<String> updatedProcessedUrls = Optional.ofNullable(processedUrls).orElse(new ArrayList<>());
            final PageTreeInfo pageTreeInfo = new PageTreeInfo(url);
            if (!updatedProcessedUrls.contains(url)) {
                updatedProcessedUrls.add(url);
                crawl(url).ifPresent(pageInfo -> {
                    mainTitle = pageInfo.getTitle();
                    pageTreeInfo.title(mainTitle).valid(true)
                            .fieldOfStudy(pageInfo.getFieldOfStudy())
                            .publishedDate(pageInfo.getPublishedDate());
//                            .flexRowPaperMeta(pageInfo.getFlexRowPaperMeta());
                    log.info("Found {} links on the web page: {}", pageInfo.getLinks().size(), url);
                    pageInfo.getLinks().forEach(link -> {
                        String nextUrl = link.attr("abs:href");
                        if (nextUrl.contains("paper") || nextUrl.contains("doi.org")) {
                            pageTreeInfo.addNodesItem(deepCrawl(nextUrl, depth - 1, updatedProcessedUrls, false));
                        }
                    });
                });
                return pageTreeInfo.getNodes() != null ? pageTreeInfo : null;
            } else {
                return null;
            }
        }

    }

    /*
     * Method to fetch web page content. Cache is used for better performance
     *
     * @see au.com.rakesh.crawler.service.CrawlerService#crawl(java.lang.String)
     */
    @Override
    @CacheResult(cacheName = "web-crawler-service")
    public Optional<PageInfo> crawl(final String url) {

        log.info("Fetching contents for url: {}", url);
        try {
            final Document doc = Jsoup.connect(url).timeout(crawlerProperties.getTimeOut())
                    .followRedirects(crawlerProperties.isFollowRedirects()).get();

            /** .select returns a list of links here **/
            final Elements links_ = doc.select("a[href]");
            final Elements links = new Elements(links_
                    .stream()
                    .filter(link -> !isRefCited(link))
                    .collect(Collectors.toList()));
            final String title = doc.title();
            log.debug("Fetched title: {}, links[{}] for url: {}", title, links.nextAll(), url);
            Element pdfURL = (Element) toFlexRowNode(doc.body().getElementById("app"),"class", "flex-row paper-meta");
            Element fieldOfStudy = pdfURL!=null
                    && pdfURL.childNodes().size()>=3 ? (Element) pdfURL.childNode(2) :null;
            //doc.body().getElementById("app").childNodes().get(0).childNodes.get(1).childNodes().get(0).childNodes().get(0).childNodes().get(0).childNodes().get(0).childNodes().get(0).childNodes().get(0).childNodes().get(3)
            Element publishedDate = (Element) toFlexRowNode(pdfURL, "data-selenium-selector", "paper-year");
            if (!(fieldOfStudy != null && fieldOfStudy.childNode(0) != null &&  fieldOfStudy.childNode(0) instanceof TextNode))
                log.debug("hello");

            return Optional.of(new PageInfo(title, url, links,
                    //pdfURL,
                    fieldOfStudy != null && fieldOfStudy.childNode(0) != null &&  fieldOfStudy.childNode(0) instanceof TextNode
                            ? ((TextNode) (fieldOfStudy.childNode(0))).text() : null,
                    publishedDate != null && publishedDate.childNode(0) != null
                            ? ((TextNode) publishedDate.childNode(0).childNode(0).childNode(0)).text() : null));
        } catch (final IOException | IllegalArgumentException e) {
            log.error(String.format("Error getting contents of url %s", url), e);
            return Optional.empty();
        }
    }

    private boolean isRefCited(Element link) {
        return toNextRefNode(link.parentNode()) != null;
    }

    private Node toNextRefNode(Node node) {
        if (node == null)
            return null;
        return node.attributes().hasKey("data-selenium-selector")
                ? node.attributes().get("data-selenium-selector").contains("reference")
                ? node
                : toNextRefNode(node.parentNode())
                : toNextRefNode(node.parentNode());
    }

    private Node toFlexRowNode(Node node, final String key, final String val) {
        if (node == null)
            return null;
        if (node.attributes() != null
                && node.attributes().hasKey(key)
                && node.attributes().get(key).equals(val))
            return node;
        Node out = null;
        for (Node child : node.childNodes()) {
            out = toFlexRowNode(child, key, val);
            if (out != null)
                break;
        }
        return out;
//        Optional<Node> mayBe = node.childNodes().stream().filter(child -> toFlexRowNode(child) != null).findFirst();
//        return mayBe.isPresent() ? mayBe.get() : null;
    }
}
