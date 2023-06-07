import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("experimental/Defaults.groovy");
        include("experimental/moduleConfig.groovy");
    }

    static String tag_format(String tag2Transform) {
        tag2Transform.replaceAll("\\.", "-")
    }

    static DEFAULT_TAG = "zenit"

}